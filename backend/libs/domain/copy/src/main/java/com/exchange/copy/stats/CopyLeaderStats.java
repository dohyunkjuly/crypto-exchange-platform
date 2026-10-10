package com.exchange.copy.stats;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * What a leader's profile shows, as of the last nightly run (V20). Returns and the success
 * rate are basis points and null until there is something to measure: a leader approved
 * today has no return, which is not the same as a return of zero.
 */
@Entity
@Table(name = "copy_leader_stats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CopyLeaderStats {

    @Id
    private UUID leaderId;

    /** One window's figures: the return, what was earned in the copy asset, the deepest fall from a high. */
    public record Window(Integer returnBps, Long pnl, Integer maxDrawdownBps) {
    }

    @Column(name = "return_7d_bps")
    private Integer return7dBps;

    @Column(name = "return_30d_bps")
    private Integer return30dBps;

    @Column(name = "return_90d_bps")
    private Integer return90dBps;

    @Column(name = "pnl_7d")
    private Long pnl7d;

    @Column(name = "pnl_30d")
    private Long pnl30d;

    @Column(name = "pnl_90d")
    private Long pnl90d;

    @Column(name = "max_drawdown_7d_bps")
    private Integer maxDrawdown7dBps;

    @Column(name = "max_drawdown_30d_bps")
    private Integer maxDrawdown30dBps;

    @Column(name = "max_drawdown_90d_bps")
    private Integer maxDrawdown90dBps;

    @Column(nullable = false)
    private int followers;

    @Column(nullable = false)
    private long fundsCopying;

    private Integer copySuccessBps;

    @Column(nullable = false)
    private int copiesCounted;

    @Column(nullable = false)
    private Instant computedAt;

    public CopyLeaderStats(UUID leaderId) {
        this.leaderId = leaderId;
    }

    /** One night's figures, whole: nothing here is added to what was there. */
    public void record(Window week, Window month, Window quarter, int followers, long fundsCopying,
                       Integer copySuccessBps, int copiesCounted, Instant computedAt) {
        this.return7dBps = week.returnBps();
        this.pnl7d = week.pnl();
        this.maxDrawdown7dBps = week.maxDrawdownBps();
        this.return30dBps = month.returnBps();
        this.pnl30d = month.pnl();
        this.maxDrawdown30dBps = month.maxDrawdownBps();
        this.return90dBps = quarter.returnBps();
        this.pnl90d = quarter.pnl();
        this.maxDrawdown90dBps = quarter.maxDrawdownBps();
        this.followers = followers;
        this.fundsCopying = fundsCopying;
        this.copySuccessBps = copySuccessBps;
        this.copiesCounted = copiesCounted;
        this.computedAt = computedAt;
    }

    public Window week() {
        return new Window(return7dBps, pnl7d, maxDrawdown7dBps);
    }

    public Window month() {
        return new Window(return30dBps, pnl30d, maxDrawdown30dBps);
    }

    public Window quarter() {
        return new Window(return90dBps, pnl90d, maxDrawdown90dBps);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CopyLeaderStats other)) {
            return false;
        }
        return leaderId != null && leaderId.equals(other.leaderId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
