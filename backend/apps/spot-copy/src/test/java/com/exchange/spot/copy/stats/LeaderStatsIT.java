package com.exchange.spot.copy.stats;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.common.id.Ulid;
import com.exchange.copy.follow.CopyFollow;
import com.exchange.copy.follow.CopyFollowRepository;
import com.exchange.copy.follow.CopyMode;
import com.exchange.copy.leader.CopyLeader;
import com.exchange.copy.leader.CopyLeaderRepository;
import com.exchange.copy.leader.LeaderStatus;
import com.exchange.copy.order.CopyOrder;
import com.exchange.copy.order.CopyOrderRepository;
import com.exchange.copy.order.CopyOrderStatus;
import com.exchange.copy.stats.CopyLeaderSnapshotRepository;
import com.exchange.copy.stats.CopyLeaderStats;
import com.exchange.copy.stats.CopyLeaderStatsRepository;
import com.exchange.spot.order.Side;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.symbol.SpotSymbolRepository;
import com.exchange.spot.trade.SpotTrade;
import com.exchange.spot.trade.SpotTradeRepository;
import com.exchange.user.identity.SubPurpose;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.identity.UserType;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetRepository;
import com.exchange.wallet.asset.AssetType;
import com.exchange.wallet.balance.BalanceService;
import com.exchange.wallet.ledger.ReferenceType;
import com.exchange.wallet.transfer.TransferService;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * A leader's nightly figures against the real tables (docs/reference/copy-trading.md): a
 * holding is valued at its market's last trade, money moved in is not a gain, a night run
 * twice counts once, and the success rate is the copies that had an outcome.
 */
@SpringBootTest(properties = {
        // Nothing here is driven by the topic or the clock: each night is run by hand.
        "spring.kafka.listener.auto-startup=false",
        "exchange.copy.close-sweep-ms=3600000",
        "exchange.copy.share-cron=-",
        "exchange.copy.stats-cron=-"})
class LeaderStatsIT {

    private static final long ONE = 100_000_000L;

    @Autowired private LeaderStatsService service;
    @Autowired private Valuation valuation;
    @Autowired private CopyLeaderRepository leaders;
    @Autowired private CopyFollowRepository follows;
    @Autowired private CopyOrderRepository copyOrders;
    @Autowired private CopyLeaderStatsRepository stats;
    @Autowired private CopyLeaderSnapshotRepository snapshots;
    @Autowired private SpotSymbolRepository markets;
    @Autowired private SpotTradeRepository trades;
    @Autowired private AssetRepository assets;
    @Autowired private UserRepository users;
    @Autowired private BalanceService balanceService;
    @Autowired private TransferService transfers;
    @Autowired private JdbcTemplate jdbc;

    private final String suffix = Long.toString(System.nanoTime() % 1_000_000L);
    private final List<Long> accounts = new ArrayList<>();
    private final UUID leaderId = Ulid.randomUuid();
    private SpotSymbol market;
    private Long usdt;
    private Long master;
    private Long lead;

    @BeforeAll
    static void requireInfrastructure() {
        for (int port : new int[] {3308, 6379, 9092}) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", port), 500);
            } catch (Exception e) {
                Assumptions.abort("infrastructure not reachable on " + port + " -- run `docker compose up -d`");
            }
        }
    }

    @BeforeEach
    void aLeaderWithAMarketOfItsOwn() {
        usdt = assets.findBySymbol("USDT").orElseThrow().getId();
        Long base = assets.save(new Asset("LS" + suffix, "leader stats test base", AssetType.CRYPTO, 8)).getId();
        SpotSymbol made = new SpotSymbol("LS" + suffix + "USDT", base, usdt, ONE, ONE, ONE, 1_000_000 * ONE, ONE, 0);
        market = markets.save(made);

        master = account(new User("stats-lead-" + suffix + "@x.dev", null));
        lead = account(sub(master, SubPurpose.LEAD, "l"));
        CopyLeader leader = new CopyLeader(leaderId, master, "stats-" + suffix, null);
        leader.setLeadUserId(lead);
        leader.setStatus(LeaderStatus.ACTIVE);
        leaders.save(leader);
    }

    @AfterEach
    void removeWhatWasMade() {
        byte[] leader = Ulid.toBytes(leaderId);
        jdbc.update("DELETE o FROM copy_orders o JOIN copy_follows f ON f.id = o.follow_id WHERE f.leader_id = ?",
                (Object) leader);
        jdbc.update("DELETE FROM copy_follows WHERE leader_id = ?", (Object) leader);
        jdbc.update("DELETE FROM copy_leader_snapshots WHERE leader_id = ?", (Object) leader);
        jdbc.update("DELETE FROM copy_leader_stats WHERE leader_id = ?", (Object) leader);
        jdbc.update("DELETE FROM copy_leaders WHERE id = ?", (Object) leader);
        jdbc.update("DELETE FROM spot_trades WHERE symbol_id = ?", market.getId());
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
    @DisplayName("three nights: the first has nothing to compare, the second values the coin it bought and takes off "
            + "the money added, the third chains on; and a night run twice is one night")
    void returnsOverTheChain() {
        // Real time, because a transfer is stamped by the database: two nights ago, tonight, tomorrow.
        Instant night1 = Instant.now().minus(Duration.ofDays(2));
        credit(lead, usdt, 1_000 * ONE, "start");
        night(night1);

        CopyLeaderStats first = mine();
        assertThat(first.getReturn7dBps()).as("one snapshot measures nothing").isNull();
        assertThat(first.getCopySuccessBps()).isNull();
        assertThat(first.getFollowers()).isZero();

        // The next day: 500 USDT becomes 5 coins that last traded at 120, and 500 more is added.
        balanceService.debit(lead, usdt, 500 * ONE, ReferenceType.WITHDRAWAL, "stats-it", "stats-it:spent:" + suffix);
        credit(lead, market.getBaseAssetId(), 5 * ONE, "coins");
        tradeAt(120 * ONE, night1.plus(Duration.ofHours(5)), 1);
        credit(master, usdt, 500 * ONE, "own");
        transfers.transfer(Ulid.randomUuid(), master, lead, usdt, 500 * ONE, master);
        Instant night2 = Instant.now().plusSeconds(5);
        night(night2);

        // 500 + 5 x 120 = 1,100 earned from 1,000, with the 500 added taken off: a tenth.
        assertThat(mine().getReturn7dBps()).isEqualTo(1_000);
        assertThat(snapshots.findByLeaderIdAndDayBetweenOrderByDayAsc(leaderId, day(night1), day(night2)))
                .extracting(s -> s.getEquity() / ONE, s -> s.getNetTransfers() / ONE)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1_000L, 0L), org.assertj.core.groups.Tuple.tuple(1_600L, 500L));

        // The same night again, after the price has moved: the snapshot is the night's, not rewritten.
        tradeAt(200 * ONE, night2.plus(Duration.ofMinutes(1)), 2);
        night(night2.plus(Duration.ofMinutes(5)));
        assertThat(mine().getReturn7dBps()).isEqualTo(1_000);

        // The third night, at 200 a coin: 1,000 + 5 x 200 = 2,000 from 1,600. Chained: 1.1 x 1.25.
        Instant night3 = night2.plus(Duration.ofDays(1));
        night(night3);
        assertThat(mine().getReturn7dBps()).isEqualTo(3_750);
        assertThat(mine().getReturn90dBps()).isEqualTo(3_750);
        assertThat(mine().getPnl30d()).as("100 earned the second day and 400 the third").isEqualTo(500 * ONE);
        assertThat(mine().getMaxDrawdown30dBps()).as("it never fell").isZero();
        assertThat(mine().getPnl7d()).isEqualTo(500 * ONE);
        assertThat(mine().getMaxDrawdown90dBps()).isZero();
    }

    @Test
    @DisplayName("followers, what their copy accounts hold, and how many of their copies filled: a skipped copy and "
            + "a closing sale are neither a success nor a failure")
    void whatTheFollowersGot() {
        Instant night = Instant.now();
        CopyFollow follow = follower("a", 400 * ONE);
        CopyFollow other = follower("b", 250 * ONE);
        credit(other.getCopyUserId(), market.getBaseAssetId(), ONE, "held");
        tradeAt(50 * ONE, night.minus(Duration.ofHours(1)), 1);
        copy(follow, Ulid.randomUuid(), CopyOrderStatus.FILLED);
        copy(follow, Ulid.randomUuid(), CopyOrderStatus.FILLED);
        copy(other, Ulid.randomUuid(), CopyOrderStatus.FILLED);
        copy(other, Ulid.randomUuid(), CopyOrderStatus.FAILED);
        copy(other, Ulid.randomUuid(), CopyOrderStatus.SKIPPED);
        copy(other, null, CopyOrderStatus.FILLED);

        night(night);

        CopyLeaderStats row = mine();
        assertThat(row.getFollowers()).isEqualTo(2);
        assertThat(row.getFundsCopying()).as("400, and 250 with a coin worth 50").isEqualTo(700 * ONE);
        assertThat(row.getCopiesCounted()).isEqualTo(4);
        assertThat(row.getCopySuccessBps()).isEqualTo(7_500);
    }

    /** One night for this test's leader alone: the job would write every leader in the database. */
    private void night(Instant now) {
        service.refresh(leaders.findById(leaderId).orElseThrow(), day(now), now, valuation.at(now));
    }

    private CopyLeaderStats mine() {
        return stats.findById(leaderId).orElseThrow();
    }

    private static LocalDate day(Instant night) {
        return night.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);
    }

    private void credit(Long userId, Long assetId, long amount, String what) {
        balanceService.credit(userId, assetId, amount, ReferenceType.DEPOSIT, "stats-it", "stats-it:" + what + ":" + suffix);
    }

    /** The market's last trade, between two accounts of the test's own. */
    private void tradeAt(long price, Instant when, long sequence) {
        trades.save(new SpotTrade(Ulid.randomUuid(), market.getId(), price, ONE, Ulid.randomUuid(), Ulid.randomUuid(),
                master, lead, true, sequence, when));
    }

    private void copy(CopyFollow follow, UUID leadOrderId, CopyOrderStatus status) {
        copyOrders.save(new CopyOrder(Ulid.randomUuid(), follow.getId(), leadOrderId, Ulid.randomUuid(), market.getId(),
                Side.BUY, status, null));
    }

    private CopyFollow follower(String name, long funded) {
        Long followerMaster = account(new User("stats-" + name + "-" + suffix + "@x.dev", null));
        Long copyAccount = account(sub(followerMaster, SubPurpose.COPY, name));
        credit(copyAccount, usdt, funded, name);
        return follows.save(new CopyFollow(Ulid.randomUuid(), leaderId, followerMaster, copyAccount, CopyMode.FIXED_RATIO, null, 1000));
    }

    private User sub(Long masterId, SubPurpose purpose, String name) {
        User sub = new User("stats-" + name + "-sub-" + suffix + "@x.dev", null);
        sub.setType(UserType.SUB);
        sub.setSubPurpose(purpose);
        sub.setParentUserId(masterId);
        return sub;
    }

    private Long account(User user) {
        Long id = users.save(user).getId();
        accounts.add(id);
        return id;
    }
}
