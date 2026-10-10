package com.exchange.spot.copy.close;

import com.exchange.copy.leader.CopyLeaderService;
import com.exchange.copy.follow.CopyCloseService;
import com.exchange.copy.follow.CloseReason;
import com.exchange.copy.follow.CopyFollow;
import com.exchange.copy.follow.CopyFollowRepository;
import com.exchange.copy.follow.FollowStatus;
import com.exchange.copy.leader.CopyLeaderRepository;
import com.exchange.copy.leader.LeaderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Starts closing the copy accounts of a leader who no longer leads, and finishes closing every
 * copy account that is closing (docs/reference/copy-trading.md). A closing account is emptied
 * only once its last sale has settled, and that happens on settlement's clock, so the only way
 * to know is to look again. Whoever started the closing -- this app, on a fifth failure, or a
 * follower stopping from the web -- it ends here.
 */
@Component
public class CloseSweeper {

    private static final Logger log = LoggerFactory.getLogger(CloseSweeper.class);

    private final CopyFollowRepository follows;
    private final CopyLeaderRepository leaders;
    private final CopyCloseService closes;

    public CloseSweeper(CopyFollowRepository follows, CopyLeaderRepository leaders, CopyCloseService closes) {
        this.follows = follows;
        this.leaders = leaders;
        this.closes = closes;
    }

    @Scheduled(fixedDelayString = "${exchange.copy.close-sweep-ms}")
    public void sweep() {
        // Staff suspended the leader, or the leader stopped: either only changed a status
        // (CopyLeaderService), and this is where the copy accounts are actually closed.
        for (CopyFollow follow : follows.findActiveOfLeadersNoLongerLeading()) {
            try {
                CloseReason reason = leaders.findById(follow.getLeaderId())
                        .filter(l -> l.getStatus() == LeaderStatus.SUSPENDED).isPresent()
                        ? CloseReason.LEADER_SUSPENDED : CloseReason.LEADER_CLOSED;
                closes.begin(follow.getId(), reason);
            } catch (RuntimeException e) {
                log.warn("could not start closing follow {} of a leader no longer leading", follow.getId(), e);
            }
        }
        for (CopyFollow follow : follows.findByStatus(FollowStatus.CLOSING)) {
            try {
                if (closes.finishIfSettled(follow.getId())) {
                    log.info("follow {} closed and returned to its owner", follow.getId());
                }
            } catch (RuntimeException e) {
                // One account failing must not stop the others; it is looked at again next time.
                log.warn("could not finish closing follow {}", follow.getId(), e);
            }
        }
    }
}
