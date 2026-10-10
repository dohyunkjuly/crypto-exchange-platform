package com.exchange.spot.copy.share;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.common.id.Ulid;
import com.exchange.copy.follow.CopyFollow;
import com.exchange.copy.follow.CopyFollowRepository;
import com.exchange.copy.follow.CopyMode;
import com.exchange.copy.follow.FollowStatus;
import com.exchange.copy.leader.CopyLeader;
import com.exchange.copy.leader.CopyLeaderRepository;
import com.exchange.copy.leader.LeaderStatus;
import com.exchange.copy.order.CopyOrder;
import com.exchange.copy.order.CopyOrderRepository;
import com.exchange.copy.order.CopyOrderStatus;
import com.exchange.copy.position.CopyPositionRepository;
import com.exchange.copy.share.CopyProfitShare;
import com.exchange.copy.share.CopyProfitShareRepository;
import com.exchange.copy.share.CopyProfitShareService;
import com.exchange.copy.share.ShareTrigger;
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
import com.exchange.wallet.balance.Balance;
import com.exchange.wallet.balance.BalanceRepository;
import com.exchange.wallet.balance.BalanceService;
import com.exchange.wallet.ledger.ReferenceType;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * The profit share against the real tables (docs/reference/copy-trading.md): paid once for
 * each unit of profit, a loss made up before anything more is due, a week settled once however
 * often it is asked, and a short account paying what it has and owing the rest.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "exchange.copy.close-sweep-ms=3600000",
        "exchange.copy.share-cron=-",
        "exchange.copy.stats-cron=-"})
class ProfitShareIT {

    private static final long ONE = 100_000_000L;

    @Autowired private CopyProfitShareService service;
    @Autowired private ProfitShareJobs jobs;
    @Autowired private CopyLeaderRepository leaders;
    @Autowired private CopyFollowRepository follows;
    @Autowired private CopyOrderRepository copyOrders;
    @Autowired private CopyPositionRepository positions;
    @Autowired private CopyProfitShareRepository shares;
    @Autowired private SpotSymbolRepository markets;
    @Autowired private SpotTradeRepository trades;
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
    private Long leaderMaster;
    private Long counterparty;
    private CopyFollow follow;
    private long sequence;

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
    void aFollowerCopyingALeader() {
        usdt = assets.findBySymbol("USDT").orElseThrow().getId();
        Long base = assets.save(new Asset("PS" + suffix, "profit share test base", AssetType.CRYPTO, 8)).getId();
        market = markets.save(new SpotSymbol("PS" + suffix + "USDT", base, usdt, ONE, ONE, ONE, 1_000_000 * ONE, ONE, 0));

        leaderMaster = account(new User("share-lead-" + suffix + "@x.dev", null));
        Long lead = account(sub(leaderMaster, SubPurpose.LEAD, "l"));
        CopyLeader leader = new CopyLeader(leaderId, leaderMaster, "share-" + suffix, null);
        leader.setLeadUserId(lead);
        leader.setStatus(LeaderStatus.ACTIVE);
        leaders.save(leader);
        counterparty = account(new User("share-other-" + suffix + "@x.dev", null));

        Long followerMaster = account(new User("share-follower-" + suffix + "@x.dev", null));
        Long copyAccount = account(sub(followerMaster, SubPurpose.COPY, "c"));
        // A tenth, agreed when the follower started; the settings row may say anything by now.
        follow = follows.save(new CopyFollow(Ulid.randomUuid(), leaderId, followerMaster, copyAccount,
                CopyMode.FIXED_RATIO, null, 1_000));
        copyOrders.save(new CopyOrder(Ulid.randomUuid(), follow.getId(), Ulid.randomUuid(), Ulid.randomUuid(),
                market.getId(), Side.BUY, CopyOrderStatus.FILLED, null));
    }

    @AfterEach
    void removeWhatWasMade() {
        byte[] leader = Ulid.toBytes(leaderId);
        jdbc.update("DELETE s FROM copy_profit_shares s JOIN copy_follows f ON f.id = s.follow_id WHERE f.leader_id = ?",
                (Object) leader);
        jdbc.update("DELETE p FROM copy_positions p JOIN copy_follows f ON f.id = p.follow_id WHERE f.leader_id = ?",
                (Object) leader);
        jdbc.update("DELETE o FROM copy_orders o JOIN copy_follows f ON f.id = o.follow_id WHERE f.leader_id = ?",
                (Object) leader);
        jdbc.update("DELETE FROM copy_follows WHERE leader_id = ?", (Object) leader);
        jdbc.update("DELETE FROM copy_leaders WHERE id = ?", (Object) leader);
        jdbc.update("DELETE FROM spot_trades WHERE symbol_id = ?", market.getId());
        for (Long id : accounts.reversed()) {
            jdbc.update("DELETE FROM wallet_ledger_entries WHERE user_id = ?", id);
            jdbc.update("DELETE FROM wallet_balances WHERE user_id = ?", id);
            jdbc.update("DELETE FROM user_users WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM spot_symbols WHERE id = ?", market.getId());
        jdbc.update("DELETE FROM wallet_assets WHERE id = ?", market.getBaseAssetId());
    }

    @Test
    @DisplayName("a round trip at a profit pays the leader a tenth of it, once for the week; a loss after that pays "
            + "nothing until it is made up; a short account pays what it has and owes the rest")
    void theFormulaOverALife() {
        LocalDate week = LocalDate.parse("2026-10-05");
        // Bought 10 at 100 with a fee of 0.1 coin, sold 10 at 150 with a fee of 1.5 USDT: 498.5 realized.
        buy(100 * ONE, 10 * ONE, ONE / 10);
        sell(150 * ONE, 10 * ONE, ONE + ONE / 2);
        usdtInCopyAccount(1_498 * ONE + ONE / 2);

        CopyProfitShare first = service.settle(follow.getId(), ShareTrigger.WEEKLY, week).orElseThrow();
        assertThat(first.getRealizedProfit()).isEqualTo(498 * ONE + ONE / 2);
        assertThat(first.getDue()).isEqualTo(49 * ONE + 85 * ONE / 100);
        assertThat(first.getPaid()).isEqualTo(first.getDue());
        assertThat(available(leaderMaster)).as("in the leader's own account").isEqualTo(first.getPaid());
        assertThat(available(follow.getCopyUserId())).isEqualTo(1_498 * ONE + ONE / 2 - first.getPaid());
        assertThat(jdbc.queryForList("SELECT entry_type FROM wallet_ledger_entries WHERE reference_type = 'PROFIT_SHARE' "
                + "AND reference_id = ? ORDER BY user_id", String.class, first.getId().toString()))
                .containsExactlyInAnyOrder("PROFIT_SHARE_OUT", "PROFIT_SHARE_IN");

        // The same week again, by the job this time: nothing moves.
        jobs.run(Instant.parse("2026-10-05T00:05:00Z"));
        assertThat(shares.findByFollowIdOrderByCreatedAtDesc(follow.getId())).hasSize(1);
        assertThat(available(leaderMaster)).isEqualTo(first.getPaid());

        // Then a loss of 500: realized is now -1.5, nothing is due, and nothing is clawed back.
        buy(150 * ONE, 10 * ONE, 0);
        sell(100 * ONE, 10 * ONE, 0);
        assertThat(service.settle(follow.getId(), ShareTrigger.WITHDRAWAL, null)).isEmpty();
        assertThat(mine().getRealizedProfit()).isEqualTo(-ONE - ONE / 2);
        assertThat(mine().getSharedProfit()).isEqualTo(first.getPaid());

        // A gain of 200 brings realized to 198.5: a tenth of that is under what was already paid.
        buy(100 * ONE, 10 * ONE, 0);
        sell(120 * ONE, 10 * ONE, 0);
        assertThat(service.settle(follow.getId(), ShareTrigger.WEEKLY, week.plusWeeks(1))).isEmpty();

        // A gain of 1,000 brings it to 1,198.5: 119.85 less the 49.85 paid is 70 due. The account
        // holds 30 USDT, so 30 is paid and 40 stays owed.
        buy(100 * ONE, 10 * ONE, 0);
        sell(200 * ONE, 10 * ONE, 0);
        usdtInCopyAccount(30 * ONE);
        CopyProfitShare shortOf = service.settle(follow.getId(), ShareTrigger.WEEKLY, week.plusWeeks(2)).orElseThrow();
        assertThat(shortOf.getDue()).isEqualTo(70 * ONE);
        assertThat(shortOf.getPaid()).isEqualTo(30 * ONE);
        assertThat(available(follow.getCopyUserId())).isZero();

        // With USDT back in the account, the next settlement finds the 40 in the same formula.
        usdtInCopyAccount(100 * ONE);
        CopyProfitShare rest = service.settle(follow.getId(), ShareTrigger.CLOSE, null).orElseThrow();
        assertThat(rest.getDue()).isEqualTo(40 * ONE);
        assertThat(rest.getPaid()).isEqualTo(40 * ONE);
        assertThat(mine().getSharedProfit()).isEqualTo(119 * ONE + 85 * ONE / 100);
        assertThat(available(leaderMaster)).isEqualTo(119 * ONE + 85 * ONE / 100);
        assertThat(positions.findByFollowIdAndAssetId(follow.getId(), market.getBaseAssetId()).orElseThrow().getLastSequence())
                .as("counted up to the last fill").isEqualTo(sequence);
    }

    @Test
    @DisplayName("the job settles the accounts still copying and leaves one that is closing to its close")
    void theJobSkipsAClosingAccount() {
        buy(100 * ONE, 10 * ONE, 0);
        sell(200 * ONE, 10 * ONE, 0);
        usdtInCopyAccount(2_000 * ONE);
        CopyFollow closing = mine();
        closing.setStatus(FollowStatus.CLOSING);
        follows.save(closing);

        jobs.run(Instant.parse("2026-10-12T00:05:00Z"));

        assertThat(shares.findByFollowIdOrderByCreatedAtDesc(follow.getId())).isEmpty();
        assertThat(available(leaderMaster)).isZero();
    }

    private CopyFollow mine() {
        return follows.findById(follow.getId()).orElseThrow();
    }

    private long available(Long userId) {
        return balances.findByUserIdAndAssetId(userId, usdt).map(Balance::getAvailable).orElse(0L);
    }

    /** Sets the copy account's USDT to exactly this, as settlement would have left it. */
    private void usdtInCopyAccount(long amount) {
        Optional<Balance> held = balances.findByUserIdAndAssetId(follow.getCopyUserId(), usdt);
        long now = held.map(Balance::getAvailable).orElse(0L);
        String key = "share-it:" + suffix + ":" + sequence + ":" + amount;
        if (amount > now) {
            balanceService.credit(follow.getCopyUserId(), usdt, amount - now, ReferenceType.DEPOSIT, "share-it", key);
        } else if (amount < now) {
            balanceService.debit(follow.getCopyUserId(), usdt, now - amount, ReferenceType.WITHDRAWAL, "share-it", key);
        }
    }

    private void buy(long price, long quantity, long feeInCoin) {
        SpotTrade trade = new SpotTrade(Ulid.randomUuid(), market.getId(), price, quantity, Ulid.randomUuid(),
                Ulid.randomUuid(), follow.getCopyUserId(), counterparty, false, ++sequence, Instant.now());
        trade.chargeFees(feeInCoin, feeInCoin == 0 ? null : market.getBaseAssetId(), 0, null);
        trades.save(trade);
    }

    private void sell(long price, long quantity, long feeInUsdt) {
        SpotTrade trade = new SpotTrade(Ulid.randomUuid(), market.getId(), price, quantity, Ulid.randomUuid(),
                Ulid.randomUuid(), counterparty, follow.getCopyUserId(), false, ++sequence, Instant.now());
        trade.chargeFees(0, null, feeInUsdt, feeInUsdt == 0 ? null : usdt);
        trades.save(trade);
    }

    private User sub(Long masterId, SubPurpose purpose, String name) {
        User sub = new User("share-" + name + "-sub-" + suffix + "@x.dev", null);
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
