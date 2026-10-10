package com.exchange.copy.leader;

import com.exchange.copy.follow.CopyCloseService;
import com.exchange.copy.leader.exception.AlreadyLeaderException;
import com.exchange.copy.leader.exception.DisplayNameTakenException;
import com.exchange.copy.leader.exception.LeaderNotFoundException;
import com.exchange.copy.leader.exception.LeaderStatusException;
import com.exchange.copy.leader.exception.NotALeaderException;
import com.exchange.copy.follow.CopyFollowRepository;
import com.exchange.copy.follow.FollowStatus;
import com.exchange.copy.leader.CopyLeader;
import com.exchange.copy.leader.CopyLeaderRepository;
import com.exchange.copy.leader.LeaderStatus;
import com.exchange.common.id.Ulid;
import com.exchange.user.identity.SubPurpose;
import com.exchange.user.subaccount.SubAccountService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who leads (docs/reference/copy-trading.md): a customer applies and leads at once, from a lead
 * account whose orders are copied; staff can only suspend and reinstate.
 *
 * <p><b>Each change here is a status and nothing else.</b> Suspending or closing a leader does
 * not close its followers' copy accounts from here: that means placing orders, and staff's app
 * has no order path. The copier sees a follow whose leader no longer leads and closes it
 * ({@code CopyFollowRepository.findActiveOfLeadersNoLongerLeading}). So this service needs
 * nothing but the tables and the sub-account it makes, and the caller's own transaction --
 * staff's audit row -- wraps it whole.
 */
@Service
public class CopyLeaderService {

    /** A leader and how many are copying them now. */
    public record Listed(CopyLeader leader, long followers) {
    }

    private static final int NAME_MIN = 3;
    private static final int NAME_MAX = 40;

    private final CopyLeaderRepository leaders;
    private final CopyFollowRepository follows;
    private final SubAccountService subAccounts;

    public CopyLeaderService(CopyLeaderRepository leaders, CopyFollowRepository follows,
                             SubAccountService subAccounts) {
        this.leaders = leaders;
        this.follows = follows;
        this.subAccounts = subAccounts;
    }

    /** Active leaders only: a suspension and a closed portfolio are nobody's to see. */
    @Transactional(readOnly = true)
    public List<Listed> listActive() {
        return leaders.findByStatusOrderByCreatedAtAsc(LeaderStatus.ACTIVE).stream().map(this::listed).toList();
    }

    /** @throws LeaderNotFoundException no such leader, or not an active one */
    @Transactional(readOnly = true)
    public Listed getActive(UUID leaderId) {
        return leaders.findById(leaderId).filter(l -> l.getStatus() == LeaderStatus.ACTIVE).map(this::listed)
                .orElseThrow(() -> new LeaderNotFoundException("no leader " + leaderId));
    }

    /** The customer's own leadership, whatever became of it. */
    @Transactional(readOnly = true)
    public Optional<Listed> mine(Long userId) {
        return leaders.findByUserId(userId).map(this::listed);
    }

    /**
     * Leads at once, from a lead account made the first time. One row a customer: a portfolio its
     * leader closed is led again on the same row, so the lead account is the same one.
     *
     * @throws IllegalArgumentException a display name that is too short or too long
     * @throws AlreadyLeaderException leading or suspended
     * @throws DisplayNameTakenException somebody else has that name
     */
    @Transactional
    public CopyLeader apply(Long userId, String displayName, String bio, String ipAddress) {
        String name = displayName == null ? "" : displayName.trim();
        if (name.length() < NAME_MIN || name.length() > NAME_MAX) {
            throw new IllegalArgumentException("a display name is " + NAME_MIN + " to " + NAME_MAX + " characters");
        }
        String about = bio == null || bio.isBlank() ? null : bio.trim();
        Optional<CopyLeader> before = leaders.findByUserId(userId);
        if (leaders.findByDisplayName(name).filter(l -> !l.getUserId().equals(userId)).isPresent()) {
            throw new DisplayNameTakenException("that display name is taken");
        }
        CopyLeader leader;
        if (before.isEmpty()) {
            leader = new CopyLeader(Ulid.randomUuid(), userId, name, about);
        } else {
            leader = before.get();
            if (leader.getStatus() != LeaderStatus.CLOSED) {
                throw new AlreadyLeaderException("this account already leads");
            }
            leader.setDisplayName(name);
            leader.setBio(about);
            leader.setStatus(LeaderStatus.ACTIVE);
            leader.setReviewedBy(null);
            leader.setReviewedAt(null);
            leader.setReviewReason(null);
        }
        if (leader.getLeadUserId() == null) {
            leader.setLeadUserId(subAccounts.createForCopyTrading(userId, SubPurpose.LEAD, name, ipAddress).getId());
        }
        return leaders.save(leader);
    }

    /**
     * No new followers, and the copier closes the copy accounts there are. The lead account is
     * untouched: its orders are its owner's, and are simply no longer copied.
     *
     * @throws LeaderStatusException not active
     */
    @Transactional
    public CopyLeader suspend(Long adminId, UUID leaderId, String reason) {
        return reviewed(in(leaderId, LeaderStatus.ACTIVE), LeaderStatus.SUSPENDED, adminId, reason);
    }

    /** @throws LeaderStatusException not suspended */
    @Transactional
    public CopyLeader reinstate(Long adminId, UUID leaderId) {
        return reviewed(in(leaderId, LeaderStatus.SUSPENDED), LeaderStatus.ACTIVE, adminId, null);
    }

    /** Whether the profile shows the lead account's holdings and trades. The leader's choice, any state. */
    @Transactional
    public CopyLeader setPortfolioShown(Long userId, boolean shown) {
        CopyLeader leader = leaders.findByUserId(userId)
                .orElseThrow(() -> new NotALeaderException("this account does not lead"));
        leader.setShowPortfolio(shown);
        return leaders.save(leader);
    }

    /**
     * The leader stops leading. Their followers' copy accounts are closed by the copier, and
     * the lead account's minimum no longer holds it: everything in it can be taken out. The
     * caller has checked the lead account is flat first ({@code CopyCloseService.requireFlat});
     * this service is also admin-bff's, which has no order path to check with.
     *
     * @throws NotALeaderException no application at all
     * @throws LeaderStatusException not active
     */
    @Transactional
    public CopyLeader close(Long userId) {
        CopyLeader leader = leaders.findByUserId(userId)
                .orElseThrow(() -> new NotALeaderException("this account does not lead"));
        if (leader.getStatus() != LeaderStatus.ACTIVE) {
            throw new LeaderStatusException("only an active leader can stop leading");
        }
        leader.setStatus(LeaderStatus.CLOSED);
        return leaders.save(leader);
    }

    private CopyLeader in(UUID leaderId, LeaderStatus expected) {
        CopyLeader leader = leaders.findById(leaderId)
                .orElseThrow(() -> new LeaderNotFoundException("no leader " + leaderId));
        if (leader.getStatus() != expected) {
            throw new LeaderStatusException("this leader is " + leader.getStatus() + ", not " + expected);
        }
        return leader;
    }

    private CopyLeader reviewed(CopyLeader leader, LeaderStatus status, Long adminId, String reason) {
        leader.setStatus(status);
        leader.setReviewedBy(adminId);
        leader.setReviewedAt(Instant.now());
        leader.setReviewReason(reason);
        return leaders.save(leader);
    }

    private Listed listed(CopyLeader leader) {
        return new Listed(leader, follows.countByLeaderIdAndStatus(leader.getId(), FollowStatus.ACTIVE));
    }
}
