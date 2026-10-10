package com.exchange.spot.copy.stats;

import com.exchange.copy.follow.CopyFollow;
import com.exchange.copy.follow.CopyFollowRepository;
import com.exchange.copy.follow.FollowStatus;
import com.exchange.copy.leader.CopyLeader;
import com.exchange.copy.order.CopyOrderRepository;
import com.exchange.copy.order.CopyOrderStatus;
import com.exchange.copy.stats.CopyLeaderSnapshot;
import com.exchange.copy.stats.CopyLeaderSnapshotRepository;
import com.exchange.copy.stats.CopyLeaderStats;
import com.exchange.copy.stats.CopyLeaderStatsRepository;
import com.exchange.copy.stats.ReturnMath;
import com.exchange.wallet.transfer.WalletTransfer;
import com.exchange.wallet.transfer.WalletTransferRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One leader's figures for one night (docs/reference/copy-trading.md): the day's snapshot of
 * the lead account, then the profile's row from the chain of snapshots and from what the
 * followers' copies did.
 *
 * <p>Safe to run again for the same day: a snapshot is written once, and the row is replaced
 * whole from what is stored.
 */
@Service
public class LeaderStatsService {

    static final int SUCCESS_WINDOW_DAYS = 30;

    private final CopyLeaderSnapshotRepository snapshots;
    private final CopyLeaderStatsRepository stats;
    private final CopyFollowRepository follows;
    private final CopyOrderRepository copyOrders;
    private final WalletTransferRepository transfers;

    public LeaderStatsService(CopyLeaderSnapshotRepository snapshots, CopyLeaderStatsRepository stats,
                       CopyFollowRepository follows, CopyOrderRepository copyOrders,
                       WalletTransferRepository transfers) {
        this.snapshots = snapshots;
        this.stats = stats;
        this.follows = follows;
        this.copyOrders = copyOrders;
        this.transfers = transfers;
    }

    /**
     * @param day the UTC day that has just ended
     * @param now when the balances are read; a snapshot is as late as the job, which is why it
     *            runs soon after midnight
     */
    @Transactional
    public void refresh(CopyLeader leader, LocalDate day, Instant now, Valuation.Prices prices) {
        if (!snapshots.existsByLeaderIdAndDay(leader.getId(), day)) {
            snapshots.save(new CopyLeaderSnapshot(leader.getId(), day, prices.equity(leader.getLeadUserId()),
                    movedInSinceLast(leader, day, now, prices), now));
        }
        List<CopyFollow> copying = follows.findByLeaderIdAndStatus(leader.getId(), FollowStatus.ACTIVE);
        long funds = 0;
        for (CopyFollow follow : copying) {
            funds += prices.equity(follow.getCopyUserId());
        }
        long filled = 0;
        long failed = 0;
        for (Object[] row : copyOrders.outcomesOfLeader(leader.getId(), now.minus(Duration.ofDays(SUCCESS_WINDOW_DAYS)))) {
            if (row[0] == CopyOrderStatus.FILLED) {
                filled = ((Number) row[1]).longValue();
            } else if (row[0] == CopyOrderStatus.FAILED) {
                failed = ((Number) row[1]).longValue();
            }
        }
        long counted = filled + failed;

        CopyLeaderStats row = stats.findById(leader.getId()).orElseGet(() -> new CopyLeaderStats(leader.getId()));
        row.record(window(leader, day, 7), window(leader, day, 30), window(leader, day, 90),
                copying.size(), funds, counted == 0 ? null : (int) (filled * 10_000 / counted), (int) counted, now);
        stats.save(row);
    }

    private CopyLeaderStats.Window window(CopyLeader leader, LocalDate day, int days) {
        List<ReturnMath.Point> points = pointsOver(leader, day, days);
        return new CopyLeaderStats.Window(ReturnMath.chainedBps(points), ReturnMath.earned(points),
                ReturnMath.maxDrawdownBps(points));
    }

    /** In minus out of the lead account since the snapshot before; nothing before the first. */
    private long movedInSinceLast(CopyLeader leader, LocalDate day, Instant now, Valuation.Prices prices) {
        Instant since = snapshots.findFirstByLeaderIdAndDayLessThanOrderByDayDesc(leader.getId(), day)
                .map(CopyLeaderSnapshot::getTakenAt).orElse(null);
        if (since == null) {
            return 0;
        }
        long net = 0;
        for (WalletTransfer transfer : transfers.touching(leader.getLeadUserId(), since, now)) {
            long worth = prices.worth(transfer.getAssetId(), transfer.getAmount());
            net += transfer.getToUserId().equals(leader.getLeadUserId()) ? worth : -worth;
        }
        return net;
    }

    /** From the snapshot {@code days} before {@code day}, or the first there is, to {@code day}'s. */
    private List<ReturnMath.Point> pointsOver(CopyLeader leader, LocalDate day, int days) {
        return snapshots.findByLeaderIdAndDayBetweenOrderByDayAsc(leader.getId(), day.minusDays(days), day)
                .stream().map(s -> new ReturnMath.Point(s.getEquity(), s.getNetTransfers())).toList();
    }
}
