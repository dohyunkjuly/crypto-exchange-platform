package com.exchange.spot.settlement.fee;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.fee.FeeProduct;
import com.exchange.fee.usertier.UserFeeTier;
import com.exchange.fee.usertier.UserFeeTierRepository;
import com.exchange.fee.volume.UserDailyVolume;
import com.exchange.fee.volume.UserDailyVolumeRepository;
import com.exchange.spot.settlement.SettlementApplication;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.symbol.SpotSymbolRepository;
import com.exchange.spot.trade.SpotTrade;
import com.exchange.spot.trade.SpotTradeRepository;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.identity.UserType;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetRepository;
import com.exchange.wallet.asset.AssetType;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The nightly run against the real schema: a master and its sub, who separately reach no level,
 * together reach VIP 1; a market quoted in another asset counts at that asset's close; a
 * counterparty with no account row is placed too; and a second run changes nothing.
 */
class FeeTierIT {

    private static final long ONE = 100_000_000L;

    @BeforeAll
    static void requireInfrastructure() {
        for (int port : new int[] {9092, 3308}) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", port), 500);
            } catch (Exception e) {
                Assumptions.abort("infrastructure not reachable on " + port + " -- run `docker compose up -d`");
            }
        }
    }

    @Test
    @DisplayName("a family's volume across markets, converted at the day's close, places the master on the ladder")
    void placesTheFamily() {
        // Six digits: symbol is varchar(20), and the cross market is base + quote.
        String suffix = Long.toString(System.nanoTime() % 1_000_000L);
        List<Long> symbolIds = new ArrayList<>();
        List<Long> assetIds = new ArrayList<>();
        List<Long> userIds = new ArrayList<>();
        try (ConfigurableApplicationContext ctx = new SpringApplication(SettlementApplication.class).run(
                "--spring.kafka.consumer.group-id=fee-tier-it-" + UUID.randomUUID(),
                "--spring.kafka.consumer.auto-offset-reset=latest", "--server.port=0")) {
            AssetRepository assets = ctx.getBean(AssetRepository.class);
            SpotSymbolRepository markets = ctx.getBean(SpotSymbolRepository.class);
            SpotTradeRepository trades = ctx.getBean(SpotTradeRepository.class);
            UserRepository users = ctx.getBean(UserRepository.class);
            UserFeeTierRepository tiers = ctx.getBean(UserFeeTierRepository.class);
            UserDailyVolumeRepository volumes = ctx.getBean(UserDailyVolumeRepository.class);
            try {
                long usdt = assets.findBySymbol("USDT").orElseThrow().getId();
                long base = assets.save(new Asset("TB" + suffix, "tier base", AssetType.CRYPTO, 8)).getId();
                long quote = assets.save(new Asset("TQ" + suffix, "tier quote", AssetType.CRYPTO, 8)).getId();
                assetIds.addAll(List.of(base, quote));
                SpotSymbol baseUsdt = market(markets, "TB" + suffix + "USDT", base, usdt, symbolIds);
                SpotSymbol quoteUsdt = market(markets, "TQ" + suffix + "USDT", quote, usdt, symbolIds);
                SpotSymbol baseQuote = market(markets, "TB" + suffix + "TQ" + suffix, base, quote, symbolIds);

                User master = users.save(new User("tier-master-" + suffix + "@x.dev", null));
                User sub = new User("tier-sub-" + suffix + "@x.dev", null);
                sub.setType(UserType.SUB);
                sub.setParentUserId(master.getId());
                sub = users.save(sub);
                userIds.addAll(List.of(master.getId(), sub.getId()));
                long stranger = System.nanoTime(); // trades, has no account row
                userIds.add(stranger);

                LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
                Instant noon = yesterday.atStartOfDay(ZoneOffset.UTC).plusSeconds(12 * 3600).toInstant();
                // The sub buys 600,000 USDT of base from the stranger: 600,000 for each of them.
                trades.save(trade(baseUsdt, ONE, 600_000 * ONE, sub.getId(), stranger, 1, noon));
                // The quote asset closes the day at 2 USDT.
                trades.save(trade(quoteUsdt, 2 * ONE, ONE, stranger, stranger + 1, 1, noon.plusSeconds(1)));
                // The master sells 250,000 quote of base: 500,000 USDT at that close.
                trades.save(trade(baseQuote, ONE, 250_000 * ONE, stranger + 1, master.getId(), 1, noon.plusSeconds(2)));

                FeeTierJobs jobs = ctx.getBean(FeeTierJobs.class);
                jobs.run(Instant.now());

                UserFeeTier placed = tiers.findByUserIdAndProduct(master.getId(), FeeProduct.SPOT).orElseThrow();
                assertThat(placed.getTierLevel()).as("600,000 + 500,000 crosses the seeded VIP 1 at 1,000,000").isEqualTo(1);
                assertThat(placed.getVolume30d()).isEqualTo(1_100_000 * ONE);
                assertThat(placed.getWindowEnd()).isEqualTo(yesterday);
                assertThat(tiers.findByUserIdAndProduct(sub.getId(), FeeProduct.SPOT)).as("a sub has no tier of its own").isEmpty();
                UserFeeTier strangers = tiers.findByUserIdAndProduct(stranger, FeeProduct.SPOT).orElseThrow();
                assertThat(strangers.getTierLevel()).isZero();
                assertThat(strangers.getVolume30d()).isEqualTo(600_002 * ONE);

                jobs.run(Instant.now());
                List<UserDailyVolume> subDays = volumes.findAll().stream()
                        .filter(v -> v.getUserId().equals(userIds.get(1))).toList();
                assertThat(subDays).as("a rerun rewrites the day, it does not add to it").hasSize(1);
                assertThat(subDays.get(0).getVolume()).isEqualTo(600_000 * ONE);
                assertThat(tiers.findByUserIdAndProduct(master.getId(), FeeProduct.SPOT).orElseThrow().getVolume30d())
                        .isEqualTo(1_100_000 * ONE);
            } finally {
                JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
                for (Long id : symbolIds) {
                    jdbc.update("DELETE FROM spot_trades WHERE symbol_id = ?", id);
                    jdbc.update("DELETE FROM spot_symbols WHERE id = ?", id);
                }
                for (Long id : assetIds) {
                    jdbc.update("DELETE FROM wallet_assets WHERE id = ?", id);
                }
                // The sub first: user_users.parent_user_id is a real foreign key, same domain.
                for (Long id : userIds.reversed()) {
                    jdbc.update("DELETE FROM fee_user_daily_volumes WHERE user_id = ?", id);
                    jdbc.update("DELETE FROM fee_user_tiers WHERE user_id = ?", id);
                    jdbc.update("DELETE FROM fee_user_daily_volumes WHERE user_id = ?", id + 1);
                    jdbc.update("DELETE FROM fee_user_tiers WHERE user_id = ?", id + 1);
                    jdbc.update("DELETE FROM user_users WHERE id = ?", id);
                }
            }
        }
    }

    private static SpotSymbol market(SpotSymbolRepository markets, String symbol, long base, long quote, List<Long> ids) {
        SpotSymbol market = markets.save(new SpotSymbol(symbol, base, quote, 1L, 1L, 1L, Long.MAX_VALUE / 2, 1L, 0));
        ids.add(market.getId());
        return market;
    }

    private static SpotTrade trade(SpotSymbol market, long price, long quantity, long buyer, long seller, long sequence,
                                   Instant at) {
        return new SpotTrade(UUID.randomUUID(), market.getId(), price, quantity, UUID.randomUUID(), UUID.randomUUID(),
                buyer, seller, false, sequence, at);
    }
}
