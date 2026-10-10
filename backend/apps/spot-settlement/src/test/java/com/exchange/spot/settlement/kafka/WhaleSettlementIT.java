package com.exchange.spot.settlement.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.common.kafka.Topics;
import com.exchange.common.id.Ulid;
import com.exchange.contracts.spot.v1.Event;
import com.exchange.contracts.spot.v1.OrderStatus;
import com.exchange.contracts.spot.v1.Side;
import com.exchange.contracts.spot.v1.TradeExecuted;
import com.exchange.spot.order.OrderType;
import com.exchange.spot.order.SpotOrder;
import com.exchange.spot.order.SpotOrderRepository;
import com.exchange.spot.order.TimeInForce;
import com.exchange.spot.settlement.SettlementApplication;
import com.exchange.spot.settlement.testsupport.ConsumerReadiness;
import com.exchange.spot.settlement.testsupport.SeededDataCleaner;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.symbol.SpotSymbolRepository;
import com.exchange.spot.trade.SpotTradeRepository;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetRepository;
import com.exchange.wallet.asset.AssetType;
import com.exchange.wallet.balance.Balance;
import com.exchange.wallet.balance.BalanceService;
import com.exchange.wallet.ledger.ReferenceType;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The exact scenario that drove this whole design: one taker order fragmenting into
 * many fills against many distinct makers -- proving the repeated-taker side collapses
 * into few transactions while every distinct maker still settles correctly, and that
 * every individual trade still gets its own {@code SpotTrade}/{@code LedgerEntry} row
 * (grouping controls transaction boundaries, not audit granularity). 20 makers, not
 * 1,000 -- same code path at a smaller, faster-to-run scale.
 *
 * <p>Skips the matching engine entirely and publishes synthetic {@code TradeExecuted}
 * events directly -- {@code spot-settlement}'s job starts at {@code events.v1}, and
 * whether the engine itself produces correct events is already covered by its own
 * extensive test suite.
 */
class WhaleSettlementIT {

    private static final String BOOTSTRAP = "localhost:9092";
    private static final int MAKER_COUNT = 20;
    private static final long PRICE = 1_000_000_000_000L;      // 10,000.00000000
    private static final long QUANTITY_PER_MAKER = 100_000_000L; // 1.0 BTC
    private static final long QUOTE_QUANTITY_PER_TRADE = PRICE;  // quantity == 1.0 exactly
    private static final int TAKER_FEE_BPS = 10;
    private static final int MAKER_FEE_BPS = -2; // a rebate, deliberately, for coverage

    @BeforeAll
    static void requireInfrastructure() throws Exception {
        requirePort("kafka", 9092);
        requirePort("mysql", 3308);
        ConsumerReadiness.abortIfSettlementAppIsRunning(BOOTSTRAP);
    }

    private static void requirePort(String service, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 500);
        } catch (Exception e) {
            Assumptions.abort(service + " not reachable -- run `docker compose up -d`");
        }
    }

    @Test
    @DisplayName("one taker order fragmenting into many fills settles correctly for the "
            + "repeated taker and every distinct maker")
    void whale_settlesCorrectlyForTheRepeatedTakerAndEveryDistinctMaker() throws Exception {
        // SpotSymbol.symbol and Asset.symbol are both varchar(20); nanoTime's full
        // digit string doesn't fit alongside a prefix, so truncate to stay
        // unique-enough within one test run.
        String suffix = Long.toString(System.nanoTime() % 10_000_000_000L);
        String symbolCode = "WHALE" + suffix;
        String assetSuffix = suffix;
        long takerUserId = System.nanoTime();
        String group = "whale-it-" + UUID.randomUUID();

        try (ConfigurableApplicationContext ctx = startSettlement(group)) {
            SpotSymbolRepository spotSymbols = ctx.getBean(SpotSymbolRepository.class);
            SpotOrderRepository spotOrders = ctx.getBean(SpotOrderRepository.class);
            SpotTradeRepository spotTrades = ctx.getBean(SpotTradeRepository.class);
            BalanceService balanceService = ctx.getBean(BalanceService.class);
            AssetRepository assets = ctx.getBean(AssetRepository.class);

            // wallet_balances.asset_id has a real FK to wallet_assets (same-domain,
            // unlike spot_orders.user_id -- see AGENTS.md's "no FK across
            // domains" rule, which does not apply within one domain).
            long baseAssetId = assets.save(new Asset("WBASE" + assetSuffix, "test base asset",
                    AssetType.CRYPTO, 8)).getId();
            long quoteAssetId = assets.save(new Asset("WQUOTE" + assetSuffix, "test quote asset",
                    AssetType.CRYPTO, 8)).getId();

            SpotSymbol symbol = spotSymbols.save(new SpotSymbol(symbolCode, baseAssetId,
                    quoteAssetId, 1L, 1L, 1L, Long.MAX_VALUE / 2, 1L, 0));
            // Opened before anything settles, and seeked to the end: these announcements are
            // transient and nothing replays them, so a consumer that joins afterwards sees
            // nothing and the assertion would pass for the wrong reason.
            KafkaConsumer<String, byte[]> settlements = settlementReader();
            // Whatever this test asserts, its rows do not outlive it.
            try {

                long takerQuoteOrderQty = QUOTE_QUANTITY_PER_TRADE * MAKER_COUNT;
                UUID takerOrderId = seedOrder(spotOrders, takerUserId, symbol.getId(),
                        com.exchange.spot.order.Side.BUY, OrderType.MARKET, 0L, takerQuoteOrderQty);
                balanceService.credit(takerUserId, quoteAssetId, takerQuoteOrderQty,
                        ReferenceType.DEPOSIT, "seed", "seed:taker:" + takerOrderId);
                balanceService.lock(takerUserId, quoteAssetId, takerQuoteOrderQty,
                        ReferenceType.ORDER, takerOrderId.toString(), "order-lock:" + takerOrderId);

                List<Long> makerUserIds = new ArrayList<>();
                List<UUID> makerOrderIds = new ArrayList<>();
                for (int i = 0; i < MAKER_COUNT; i++) {
                    long makerUserId = System.nanoTime() + i + 1;
                    UUID makerOrderId = seedOrder(spotOrders, makerUserId, symbol.getId(),
                            com.exchange.spot.order.Side.SELL, OrderType.LIMIT,
                            QUANTITY_PER_MAKER, null);
                    balanceService.credit(makerUserId, baseAssetId, QUANTITY_PER_MAKER,
                            ReferenceType.DEPOSIT, "seed", "seed:maker:" + makerOrderId);
                    balanceService.lock(makerUserId, baseAssetId, QUANTITY_PER_MAKER,
                            ReferenceType.ORDER, makerOrderId.toString(), "order-lock:" + makerOrderId);
                    makerUserIds.add(makerUserId);
                    makerOrderIds.add(makerOrderId);
                }

                // events.v1 is long-retained and shared across every test run against this
                // latest is resolved at ASSIGNMENT time, so publishing before the group
                // has joined means the consumer starts past these events and never sees
                // them. A fixed sleep was a guess at how long that takes; it held locally
                // and failed on CI. See ConsumerReadiness.
                ConsumerReadiness.awaitLive(ctx, BOOTSTRAP, group, symbolCode, Duration.ofMinutes(1));
                RecordMetadata last = publishTrades(symbolCode, takerOrderId, takerUserId,
                        makerOrderIds, makerUserIds);

                // Not the taker's balance: fills and balances commit concurrently, so a settled
                // balance says nothing about the order row. See awaitSettledPast.
                ConsumerReadiness.awaitSettledPast(BOOTSTRAP, group,
                        new TopicPartition(last.topic(), last.partition()), last.offset(),
                        Duration.ofSeconds(90),
                        () -> "taker filled "
                                + spotOrders.findById(takerOrderId)
                                        .map(o -> String.valueOf(o.getFilledQuantity()))
                                        .orElse("?")
                                + " of " + (QUANTITY_PER_MAKER * MAKER_COUNT)
                                + ", trades persisted "
                                + spotTrades.findByBuyerOrderIdOrSellerOrderId(
                                        takerOrderId, takerOrderId).size()
                                + " of " + MAKER_COUNT);

                SpotOrder taker = spotOrders.findById(takerOrderId).orElseThrow();
                assertThat(taker.getFilledQuantity()).isEqualTo(QUANTITY_PER_MAKER * MAKER_COUNT);
                assertThat(taker.getCumulativeQuoteQuantity()).isEqualTo(takerQuoteOrderQty);
                assertThat(taker.getStatus()).isEqualTo(com.exchange.spot.order.OrderStatus.FILLED);

                Balance takerQuote = balanceForUpdate(ctx, takerUserId, quoteAssetId);
                assertThat(takerQuote.getLocked()).isEqualTo(0L); // fully settled, no dust
                long takerFeeTotal = (QUANTITY_PER_MAKER * TAKER_FEE_BPS / 10_000) * MAKER_COUNT;
                Balance takerBase = balanceForUpdate(ctx, takerUserId, baseAssetId);
                assertThat(takerBase.getAvailable())
                        .isEqualTo(QUANTITY_PER_MAKER * MAKER_COUNT - takerFeeTotal);

                long rebatePerTrade = Math.abs(QUOTE_QUANTITY_PER_TRADE * MAKER_FEE_BPS / 10_000);
                for (int i = 0; i < MAKER_COUNT; i++) {
                    SpotOrder maker = spotOrders.findById(makerOrderIds.get(i)).orElseThrow();
                    assertThat(maker.getStatus()).isEqualTo(com.exchange.spot.order.OrderStatus.FILLED);
                    assertThat(maker.getFilledQuantity()).isEqualTo(QUANTITY_PER_MAKER);

                    Balance makerBase = balanceForUpdate(ctx, makerUserIds.get(i), baseAssetId);
                    assertThat(makerBase.getLocked()).isEqualTo(0L);
                    Balance makerQuote = balanceForUpdate(ctx, makerUserIds.get(i), quoteAssetId);
                    assertThat(makerQuote.getAvailable())
                            .isEqualTo(QUOTE_QUANTITY_PER_TRADE + rebatePerTrade);
                }

                // Every one of the MAKER_COUNT trades got its own row -- grouping collapsed
                // transaction boundaries, never audit granularity. buyerOrderId is the
                // SAME taker order on every one of them, so this alone already selects
                // every trade in the batch -- no need to also query per maker.
                assertThat(spotTrades.findByBuyerOrderIdOrSellerOrderId(takerOrderId, takerOrderId))
                        .hasSize(MAKER_COUNT);

                assertSettlementsAnnounced(settlements, takerUserId, makerUserIds);
            } finally {
                settlements.close();
                SeededDataCleaner.purge(ctx, symbol);
            }
        }
    }


    /** Reads exchange.spot.settlements.v1 from the end, so only what this test causes is seen. */
    private static KafkaConsumer<String, byte[]> settlementReader() {
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                org.apache.kafka.clients.consumer.ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP,
                org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG,
                        "settlements-probe-" + UUID.randomUUID(),
                org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                        org.apache.kafka.common.serialization.StringDeserializer.class,
                org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                        org.apache.kafka.common.serialization.ByteArrayDeserializer.class));
        List<org.apache.kafka.common.TopicPartition> partitions =
                consumer.partitionsFor(com.exchange.common.kafka.Topics.SPOT_SETTLEMENTS).stream()
                        .map(p -> new org.apache.kafka.common.TopicPartition(p.topic(), p.partition()))
                        .toList();
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        consumer.poll(Duration.ofMillis(200)); // force the seek to resolve
        return consumer;
    }

    /**
     * The whole chain, end to end: a fill settles, the fee is computed, the row is written, and
     * the announcement carries the amount that was actually charged.
     *
     * <p>The maker rate here is negative, so this is also the assertion that a rebate survives
     * as a rebate -- through Money.applyBps, past the CHECK that used to forbid a negative fee
     * (V4__spot.sql), onto the row, and out onto the wire. Every link in that chain reported a charge
     * instead until it was fixed.
     */
    private static void assertSettlementsAnnounced(KafkaConsumer<String, byte[]> settlements,
                                                   long takerUserId, List<Long> makerUserIds)
            throws Exception {
        List<com.exchange.contracts.spot.v1.Settlement> received = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && received.size() < MAKER_COUNT * 2) {
            for (var record : settlements.poll(Duration.ofMillis(500))) {
                received.add(com.exchange.contracts.spot.v1.Settlement.parseFrom(record.value()));
            }
        }

        List<com.exchange.contracts.spot.v1.Settlement> fills = received.stream()
                .filter(com.exchange.contracts.spot.v1.Settlement::hasFillSettled)
                .toList();
        // One per participant per fill: the taker gets MAKER_COUNT, each maker gets one.
        assertThat(fills).as("a FillSettled per side of every fill").hasSize(MAKER_COUNT * 2);
        assertThat(fills).filteredOn(f -> f.getUserId() == takerUserId).hasSize(MAKER_COUNT);
        assertThat(fills).extracting(com.exchange.contracts.spot.v1.Settlement::getUserId)
                .containsAll(makerUserIds);

        // The taker crossed, so pays; the makers rested, so are paid. A rebate reaching the
        // wire as a positive number is the bug this asserts against.
        assertThat(fills).filteredOn(f -> f.getUserId() == takerUserId)
                .allSatisfy(f -> {
                    assertThat(f.getFillSettled().getIsMaker()).isFalse();
                    assertThat(f.getFillSettled().getFee()).isPositive();
                });
        assertThat(fills).filteredOn(f -> f.getUserId() != takerUserId)
                .allSatisfy(f -> {
                    assertThat(f.getFillSettled().getIsMaker()).isTrue();
                    assertThat(f.getFillSettled().getFee()).isNegative();
                });

        // And the balances were announced too -- the other half of the topic.
        assertThat(received).anyMatch(com.exchange.contracts.spot.v1.Settlement::hasBalanceChanged);
    }

    /** Returns the last event's position; every event shares the symbol's partition. */
    private RecordMetadata publishTrades(String symbolCode, UUID takerOrderId, long takerUserId,
                                         List<UUID> makerOrderIds, List<Long> makerUserIds)
            throws Exception {
        RecordMetadata lastSent = null;
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class))) {
            for (int i = 0; i < MAKER_COUNT; i++) {
                boolean last = i == MAKER_COUNT - 1;
                TradeExecuted trade = TradeExecuted.newBuilder()
                        .setTradeId(bytes(UUID.randomUUID()))
                        .setTakerOrderId(bytes(takerOrderId))
                        .setMakerOrderId(bytes(makerOrderIds.get(i)))
                        .setTakerUserId(takerUserId)
                        .setMakerUserId(makerUserIds.get(i))
                        .setTakerSide(Side.SIDE_BUY)
                        .setPrice(PRICE)
                        .setQuantity(QUANTITY_PER_MAKER)
                        .setQuoteQuantity(QUOTE_QUANTITY_PER_TRADE)
                        .setTakerFeeBps(TAKER_FEE_BPS)
                        .setMakerFeeBps(MAKER_FEE_BPS)
                        .setTakerStatus(last ? OrderStatus.ORDER_STATUS_FILLED
                                : OrderStatus.ORDER_STATUS_PARTIALLY_FILLED)
                        .setMakerStatus(OrderStatus.ORDER_STATUS_FILLED)
                        .build();
                Event event = Event.newBuilder()
                        .setSymbol(symbolCode)
                        .setSequence(i + 1)
                        .setTimestampUs(System.currentTimeMillis() * 1_000)
                        .setTradeExecuted(trade)
                        .build();
                lastSent = producer.send(new ProducerRecord<>(Topics.SPOT_EVENTS, symbolCode,
                        event.toByteArray())).get();
            }
        }
        return lastSent;
    }

    private static UUID seedOrder(SpotOrderRepository repo, long userId, Long symbolId,
                                  com.exchange.spot.order.Side side, OrderType type,
                                  long quantity, Long quoteOrderQty) {
        UUID id = Ulid.randomUuid();
        SpotOrder order = new SpotOrder(id, userId, symbolId, side, type,
                type == OrderType.MARKET ? TimeInForce.IOC : TimeInForce.GTC,
                type == OrderType.LIMIT ? PRICE : null, null, quantity, quoteOrderQty,
                Math.max(TAKER_FEE_BPS, MAKER_FEE_BPS), Math.max(TAKER_FEE_BPS, MAKER_FEE_BPS),
                null, null);
        repo.save(order);
        return id;
    }

    /** {@code BalanceService} has no plain get-by-id -- {@code list} is the closest thing,
     *  filtered to the one asset under test. */
    private static Balance balanceForUpdate(ConfigurableApplicationContext ctx, long userId,
                                            long assetId) {
        return balanceForUpdateOrNull(ctx, userId, assetId)
                .orElseThrow(() -> new IllegalStateException(
                        "no balance row for user " + userId + " asset " + assetId));
    }

    private static Optional<Balance> balanceForUpdateOrNull(ConfigurableApplicationContext ctx,
                                                             long userId, long assetId) {
        return ctx.getBean(BalanceService.class).list(userId).stream()
                .filter(b -> b.getAssetId() == assetId)
                .findFirst();
    }

    private static ConfigurableApplicationContext startSettlement(String group) {
        return new SpringApplication(SettlementApplication.class)
                .run("--spring.kafka.consumer.group-id=" + group,
                     "--spring.kafka.bootstrap-servers=" + BOOTSTRAP,
                     "--spring.kafka.consumer.auto-offset-reset=latest",
                     "--server.port=0");
    }

    private static com.google.protobuf.ByteString bytes(UUID id) {
        return com.google.protobuf.ByteString.copyFrom(Ulid.toBytes(id));
    }
}
