package com.exchange.spot.copy;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.common.id.Ulid;
import com.exchange.contracts.spot.v1.Event;
import com.exchange.contracts.spot.v1.OrderCanceled;
import com.exchange.contracts.spot.v1.OrderStatus;
import com.exchange.contracts.spot.v1.TradeExecuted;
import com.exchange.copy.follow.CloseReason;
import com.exchange.copy.follow.CopyFollow;
import com.exchange.copy.follow.CopyFollowRepository;
import com.exchange.copy.follow.CopyMode;
import com.exchange.copy.follow.FollowStatus;
import com.exchange.copy.leader.CopyLeader;
import com.exchange.copy.leader.CopyLeaderRepository;
import com.exchange.copy.leader.LeaderStatus;
import com.exchange.copy.leadorder.CopyLeadOrder;
import com.exchange.copy.leadorder.CopyLeadOrderRepository;
import com.exchange.copy.order.CopyOrder;
import com.exchange.copy.order.CopyOrderReason;
import com.exchange.copy.order.CopyOrderRepository;
import com.exchange.copy.order.CopyOrderStatus;
import com.exchange.spot.copy.close.CloseSweeper;
import com.exchange.spot.copy.kafka.CopyEventHandler;
import com.exchange.spot.order.OrderType;
import com.exchange.spot.order.Side;
import com.exchange.spot.order.SpotOrder;
import com.exchange.spot.order.SpotOrderRepository;
import com.exchange.spot.order.TimeInForce;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.symbol.SpotSymbolRepository;
import com.exchange.spot.symbol.SymbolStatus;
import com.exchange.user.identity.SubPurpose;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.identity.UserType;
import com.exchange.user.kyc.KycLevel;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetRepository;
import com.exchange.wallet.asset.AssetType;
import com.exchange.wallet.balance.Balance;
import com.exchange.wallet.balance.BalanceRepository;
import com.exchange.wallet.balance.BalanceService;
import com.exchange.wallet.ledger.ReferenceType;
import com.google.protobuf.ByteString;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The copier against the real tables and the real order path (docs/reference/copy-trading.md):
 * a closed lead order becomes each follower's order, once; a copy's outcome is kept; five
 * failures close the account, which is emptied only once it has settled.
 *
 * <p>The events are handed to the handler rather than published. Published, the running
 * settlement app would read them too, and an event for an order with no row stops its
 * partition. The copies themselves are real orders, on a market of the test's own.
 *
 * <p>Skips when spot-settlement is running: it would settle the copies as the test does by
 * hand, and two writers on one balance is how a lock goes negative.
 */
@SpringBootTest(properties = {
        // Driven by hand: the listener would bring in every other event on the topic, and the
        // sweep would close an account between two assertions.
        "spring.kafka.listener.auto-startup=false",
        "exchange.copy.close-sweep-ms=3600000",
        "exchange.copy.share-cron=-",
        "exchange.copy.stats-cron=-"})
class CopierIT {

    private static final long ONE = 100_000_000L;
    private static final String BOOTSTRAP = "localhost:9092";

    @Autowired private CopyEventHandler handler;
    @Autowired private CloseSweeper sweeper;
    @Autowired private CopyLeaderRepository leaders;
    @Autowired private CopyFollowRepository follows;
    @Autowired private CopyLeadOrderRepository leadOrders;
    @Autowired private CopyOrderRepository copyOrders;
    @Autowired private SpotOrderRepository orders;
    @Autowired private SpotSymbolRepository markets;
    @Autowired private AssetRepository assets;
    @Autowired private UserRepository users;
    @Autowired private BalanceService balanceService;
    @Autowired private BalanceRepository balances;
    @Autowired private JdbcTemplate jdbc;

    private final String suffix = Long.toString(System.nanoTime() % 1_000_000L);
    private final List<Long> accounts = new ArrayList<>();
    private final UUID leaderId = Ulid.randomUuid();
    private SpotSymbol market;
    private Long usdt;
    private long sequence;

    @BeforeAll
    static void requireInfrastructure() throws Exception {
        for (int port : new int[] {3308, 6379, 9092}) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", port), 500);
            } catch (Exception e) {
                Assumptions.abort("infrastructure not reachable on " + port + " -- run `docker compose up -d`");
            }
        }
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP))) {
            ConsumerGroupDescription settlement = admin.describeConsumerGroups(List.of("spot-settlement"))
                    .all().get().get("spot-settlement");
            if (settlement != null && !settlement.members().isEmpty()) {
                Assumptions.abort("spot-settlement is running and would settle this test's copies too -- "
                        + "run `docker compose stop spot-settlement spot-matching-engine spot-copy` first");
            }
        }
    }

    @BeforeEach
    void seed() {
        usdt = assets.findBySymbol("USDT").orElseThrow().getId();
        Long base = assets.save(new Asset("CB" + suffix, "copier test base", AssetType.CRYPTO, 8)).getId();
        // Tick 0.01, step 0.001, minimum 0.001, minimum notional 10.
        SpotSymbol made = new SpotSymbol("CB" + suffix + "USDT", base, usdt, 1_000_000L, 100_000L, 100_000L,
                1_000_000 * ONE, 10 * ONE, 0);
        made.setStatus(SymbolStatus.TRADING);
        made.setMarketOrdersEnabled(true);
        market = markets.save(made);

        Long leaderMaster = account(new User("copier-lead-" + suffix + "@x.dev", null));
        Long leadAccount = account(sub(leaderMaster, SubPurpose.LEAD, "l"));
        CopyLeader leader = new CopyLeader(leaderId, leaderMaster, "lead-" + suffix, null);
        leader.setLeadUserId(leadAccount);
        leader.setStatus(LeaderStatus.ACTIVE);
        leaders.save(leader);
    }

    @AfterEach
    void removeWhatWasMade() {
        byte[] leader = Ulid.toBytes(leaderId);
        jdbc.update("DELETE o FROM copy_orders o JOIN copy_follows f ON f.id = o.follow_id WHERE f.leader_id = ?",
                (Object) leader);
        jdbc.update("DELETE FROM copy_lead_orders WHERE leader_id = ?", (Object) leader);
        jdbc.update("DELETE FROM copy_follows WHERE leader_id = ?", (Object) leader);
        jdbc.update("DELETE FROM copy_leaders WHERE id = ?", (Object) leader);
        jdbc.update("DELETE FROM spot_command_outbox WHERE symbol = ?", market.getSymbol());
        jdbc.update("DELETE FROM spot_orders WHERE symbol_id = ?", market.getId());
        // Subs before their master: parent_user_id is a real foreign key, same domain.
        for (Long id : accounts.reversed()) {
            jdbc.update("DELETE FROM wallet_transfers WHERE from_user_id = ? OR to_user_id = ?", id, id);
            jdbc.update("DELETE FROM wallet_ledger_entries WHERE user_id = ?", id);
            jdbc.update("DELETE FROM wallet_balances WHERE user_id = ?", id);
            jdbc.update("DELETE FROM user_users WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM spot_symbols WHERE id = ?", market.getId());
        jdbc.update("DELETE FROM wallet_assets WHERE id = ?", market.getBaseAssetId());
    }

    @Test
    @DisplayName("a lead buy is copied once in each mode, a lead sell is a share of what each holds, "
            + "and each copy keeps its own outcome")
    void copiesAndKeepsOutcomes() {
        CopyFollow ratio = follower("ratio", CopyMode.FIXED_RATIO, null, 1_000 * ONE);
        CopyFollow fixed = follower("fixed", CopyMode.FIXED_AMOUNT, 20 * ONE, 1_000 * ONE);

        // The leader spent half their USDT buying 5 at 100, against two resting orders. The
        // engine stamps both trades FILLED, as it does for every trade of a taker: nothing is
        // copied until what filled covers the order. The last trade is delivered twice.
        UUID buy = leadOrder(Side.BUY, CopyLeadOrder.WHOLE / 2, 500 * ONE, 5 * ONE);
        handler.handle(List.of(trade(buy, 3 * ONE, 300 * ONE, OrderStatus.ORDER_STATUS_FILLED)));
        assertThat(rows(ratio)).as("the first of two trades is not the order closing").isEmpty();
        Event last = trade(buy, 2 * ONE, 200 * ONE, OrderStatus.ORDER_STATUS_FILLED);
        handler.handle(List.of(last));
        handler.handle(List.of(last));

        SpotOrder ratioBuy = copyOf(ratio, buy);
        assertThat(ratioBuy.getSide()).isEqualTo(Side.BUY);
        assertThat(ratioBuy.getOrderType()).isEqualTo(OrderType.LIMIT);
        assertThat(ratioBuy.getTimeInForce()).isEqualTo(TimeInForce.IOC);
        assertThat(ratioBuy.getPrice()).as("the leader's 100 plus the 0.5% cap").isEqualTo(100_50_000_000L);
        assertThat(ratioBuy.getQuantity()).as("half of 1,000, at 100.50").isEqualTo(4_975 * (ONE / 1_000));
        assertThat(ratioBuy.getUserId()).isEqualTo(ratio.getCopyUserId());
        assertThat(copyOf(fixed, buy).getQuantity()).as("20, at 100.50").isEqualTo(199 * (ONE / 1_000));
        assertThat(copyOrders.count()).as("the second delivery copied nothing").isGreaterThanOrEqualTo(2);
        assertThat(rows(ratio)).hasSize(1);
        assertThat(rows(fixed)).hasSize(1);
        assertThat(leadOrders.findById(buy).orElseThrow().getCopiedAt()).isNotNull();

        // One copy trades, the other closes unfilled.
        handler.handle(List.of(trade(ratioBuy.getId(), ONE, 100 * ONE, OrderStatus.ORDER_STATUS_PARTIALLY_FILLED),
                cancel(copyOf(fixed, buy).getId())));
        assertThat(rows(ratio).get(0).getStatus()).isEqualTo(CopyOrderStatus.FILLED);
        assertThat(rows(fixed).get(0).getStatus()).isEqualTo(CopyOrderStatus.FAILED);
        assertThat(rows(fixed).get(0).getReason()).isEqualTo(CopyOrderReason.NOT_FILLED);
        assertThat(follows.findById(ratio.getId()).orElseThrow().getFailuresInRow()).isZero();
        assertThat(follows.findById(fixed.getId()).orElseThrow().getFailuresInRow()).isEqualTo(1);

        // The leader sells a fifth of their holding. One follower holds 0.6, the other missed the buy.
        balanceService.credit(ratio.getCopyUserId(), market.getBaseAssetId(), 6 * ONE / 10, ReferenceType.DEPOSIT,
                "copier-it", "copier-it:base:" + suffix);
        UUID sell = leadOrder(Side.SELL, CopyLeadOrder.WHOLE / 5, ONE, ONE);
        handler.handle(List.of(trade(sell, ONE, 100 * ONE, OrderStatus.ORDER_STATUS_FILLED)));

        SpotOrder ratioSell = copyOf(ratio, sell);
        assertThat(ratioSell.getOrderType()).isEqualTo(OrderType.MARKET);
        assertThat(ratioSell.getSide()).isEqualTo(Side.SELL);
        assertThat(ratioSell.getQuantity()).as("a fifth of 0.6").isEqualTo(12 * ONE / 100);
        CopyOrder missed = copyOrders.findAll().stream()
                .filter(c -> sell.equals(c.getLeadOrderId()) && c.getFollowId().equals(fixed.getId()))
                .findFirst().orElseThrow();
        assertThat(missed.getStatus()).isEqualTo(CopyOrderStatus.SKIPPED);
        assertThat(missed.getReason()).isEqualTo(CopyOrderReason.NOTHING_TO_SELL);
        assertThat(follows.findById(fixed.getId()).orElseThrow().getFailuresInRow())
                .as("a sell with nothing to sell is not a failure").isEqualTo(1);
    }

    @Test
    @DisplayName("five copies in a row that do not fill close the account: its holding is sold, and it is "
            + "returned to its owner only once that has settled")
    void fiveFailuresClose() {
        CopyFollow follow = follower("five", CopyMode.FIXED_AMOUNT, 20 * ONE, 1_000 * ONE);
        balanceService.credit(follow.getCopyUserId(), market.getBaseAssetId(), ONE / 2, ReferenceType.DEPOSIT,
                "copier-it", "copier-it:base:" + suffix);

        for (int i = 0; i < 5; i++) {
            assertThat(follows.findById(follow.getId()).orElseThrow().getStatus()).isEqualTo(FollowStatus.ACTIVE);
            UUID buy = leadOrder(Side.BUY, CopyLeadOrder.WHOLE / 2, 500 * ONE, 5 * ONE);
            handler.handle(List.of(trade(buy, 5 * ONE, 500 * ONE, OrderStatus.ORDER_STATUS_FILLED)));
            handler.handle(List.of(cancel(copyOf(follow, buy).getId())));
        }

        CopyFollow closing = follows.findById(follow.getId()).orElseThrow();
        assertThat(closing.getStatus()).isEqualTo(FollowStatus.CLOSING);
        assertThat(closing.getCloseReason()).isEqualTo(CloseReason.FAILURES);
        CopyOrder sale = rows(follow).stream().filter(c -> c.getLeadOrderId() == null).findFirst().orElseThrow();
        SpotOrder saleOrder = orders.findById(sale.getOrderId()).orElseThrow();
        assertThat(saleOrder.getOrderType()).isEqualTo(OrderType.MARKET);
        assertThat(saleOrder.getQuantity()).as("everything it held").isEqualTo(ONE / 2);

        // A sixth lead order is not copied: the account is no longer following.
        UUID after = leadOrder(Side.BUY, CopyLeadOrder.WHOLE / 2, 500 * ONE, 5 * ONE);
        handler.handle(List.of(trade(after, 5 * ONE, 500 * ONE, OrderStatus.ORDER_STATUS_FILLED)));
        assertThat(rows(follow)).noneMatch(c -> after.equals(c.getLeadOrderId()));

        // Its orders are still open and its funds still locked: nothing may be returned yet.
        sweeper.sweep();
        assertThat(follows.findById(follow.getId()).orElseThrow().getStatus()).isEqualTo(FollowStatus.CLOSING);

        // What settlement does when every one of those orders is cancelled unfilled.
        jdbc.update("UPDATE spot_orders SET status = 'CANCELED' WHERE user_id = ?", follow.getCopyUserId());
        jdbc.update("UPDATE wallet_balances SET available = available + locked, locked = 0 WHERE user_id = ?",
                follow.getCopyUserId());
        sweeper.sweep();

        CopyFollow closed = follows.findById(follow.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(FollowStatus.CLOSED);
        assertThat(closed.getClosedAt()).isNotNull();
        assertThat(balances.findByUserId(follow.getCopyUserId())).allMatch(b -> b.getTotal() == 0);
        assertThat(held(follow.getUserId(), usdt)).as("the follower has their USDT back").isEqualTo(1_000 * ONE);
        assertThat(held(follow.getUserId(), market.getBaseAssetId())).as("and what could not be sold, as it is")
                .isEqualTo(ONE / 2);
    }

    @Test
    @DisplayName("a leader staff suspended, or who stopped, only had a status changed: the sweep is what closes "
            + "the copy accounts, and says which it was")
    void aLeaderNoLongerLeadingHasItsFollowersClosed() {
        CopyFollow follow = follower("gone", CopyMode.FIXED_RATIO, null, 1_000 * ONE);
        balanceService.credit(follow.getCopyUserId(), market.getBaseAssetId(), ONE / 2, ReferenceType.DEPOSIT,
                "copier-it", "copier-it:gone-base:" + suffix);

        sweeper.sweep();
        assertThat(follows.findById(follow.getId()).orElseThrow().getStatus()).as("the leader still leads")
                .isEqualTo(FollowStatus.ACTIVE);

        CopyLeader leader = leaders.findById(leaderId).orElseThrow();
        leader.setStatus(LeaderStatus.SUSPENDED);
        leaders.save(leader);
        sweeper.sweep();

        CopyFollow closing = follows.findById(follow.getId()).orElseThrow();
        assertThat(closing.getStatus()).isEqualTo(FollowStatus.CLOSING);
        assertThat(closing.getCloseReason()).isEqualTo(CloseReason.LEADER_SUSPENDED);
        CopyOrder sale = rows(follow).stream().filter(c -> c.getLeadOrderId() == null).findFirst().orElseThrow();
        assertThat(orders.findById(sale.getOrderId()).orElseThrow().getQuantity()).as("everything it held")
                .isEqualTo(ONE / 2);

        // A second follower, when the leader stops of their own accord.
        CopyFollow other = follower("gone2", CopyMode.FIXED_RATIO, null, 1_000 * ONE);
        leader = leaders.findById(leaderId).orElseThrow();
        leader.setStatus(LeaderStatus.CLOSED);
        leaders.save(leader);
        sweeper.sweep();

        CopyFollow closed = follows.findById(other.getId()).orElseThrow();
        assertThat(closed.getCloseReason()).isEqualTo(CloseReason.LEADER_CLOSED);
        assertThat(closed.getStatus()).as("it held nothing to sell, so the same sweep returns it")
                .isEqualTo(FollowStatus.CLOSED);
        assertThat(held(other.getUserId(), usdt)).isEqualTo(1_000 * ONE);
        assertThat(follows.findById(follow.getId()).orElseThrow().getCloseReason())
                .as("an account already closing keeps the reason it started with").isEqualTo(CloseReason.LEADER_SUSPENDED);
    }

    /** A follower who may trade, with a funded copy account following the test's leader. */
    private CopyFollow follower(String name, CopyMode mode, Long amountPerOrder, long funded) {
        User master = new User("copier-" + name + "-" + suffix + "@x.dev", null);
        master.setEmailVerified(true);
        master.setKycLevel(KycLevel.BASIC);
        master.setMfaEnabled(true);
        Long masterId = account(master);
        Long copyAccount = account(sub(masterId, SubPurpose.COPY, name));
        balanceService.credit(copyAccount, usdt, funded, ReferenceType.DEPOSIT, "copier-it",
                "copier-it:" + name + ":" + suffix);
        return follows.save(new CopyFollow(Ulid.randomUuid(), leaderId, masterId, copyAccount, mode, amountPerOrder, 1000));
    }

    private User sub(Long masterId, SubPurpose purpose, String name) {
        User sub = new User("copier-" + name + "-sub-" + suffix + "@x.dev", null);
        sub.setType(UserType.SUB);
        sub.setSubPurpose(purpose);
        sub.setParentUserId(masterId);
        sub.setEmailVerified(true);
        return sub;
    }

    private Long account(User user) {
        Long id = users.save(user).getId();
        accounts.add(id);
        return id;
    }

    /** As the order path's hook leaves one when a lead account places an order. */
    private UUID leadOrder(Side side, long fraction, long amount, long quantity) {
        UUID id = Ulid.randomUuid();
        leadOrders.save(new CopyLeadOrder(id, leaderId, market.getId(), side, fraction, amount, quantity));
        return id;
    }

    private SpotOrder copyOf(CopyFollow follow, UUID leadOrderId) {
        CopyOrder copy = rows(follow).stream().filter(c -> leadOrderId.equals(c.getLeadOrderId()))
                .findFirst().orElseThrow(() -> new AssertionError("no copy of " + leadOrderId));
        assertThat(copy.getOrderId()).as("the copy was placed").isNotNull();
        return orders.findById(copy.getOrderId()).orElseThrow();
    }

    private List<CopyOrder> rows(CopyFollow follow) {
        return copyOrders.findAll().stream().filter(c -> c.getFollowId().equals(follow.getId())).toList();
    }

    private long held(Long userId, Long assetId) {
        return balances.findByUserIdAndAssetId(userId, assetId).map(Balance::getAvailable).orElse(0L);
    }

    /** The order as the taker of one trade. */
    private Event trade(UUID orderId, long quantity, long quote, OrderStatus status) {
        return Event.newBuilder().setSymbol(market.getSymbol()).setSequence(++sequence)
                .setTradeExecuted(TradeExecuted.newBuilder().setTradeId(bytes(Ulid.randomUuid()))
                        .setTakerOrderId(bytes(orderId)).setMakerOrderId(bytes(Ulid.randomUuid()))
                        .setQuantity(quantity).setQuoteQuantity(quote).setTakerStatus(status)
                        .setMakerStatus(OrderStatus.ORDER_STATUS_FILLED)).build();
    }

    private Event cancel(UUID orderId) {
        return Event.newBuilder().setSymbol(market.getSymbol()).setSequence(++sequence)
                .setOrderCanceled(OrderCanceled.newBuilder().setOrderId(bytes(orderId))).build();
    }

    private static ByteString bytes(UUID id) {
        return ByteString.copyFrom(Ulid.toBytes(id));
    }
}
