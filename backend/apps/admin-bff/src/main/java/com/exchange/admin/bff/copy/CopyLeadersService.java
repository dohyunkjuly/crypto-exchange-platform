package com.exchange.admin.bff.copy;

import com.exchange.admin.audit.AdminAction;
import com.exchange.admin.bff.audit.AdminAuditService;
import com.exchange.admin.bff.common.errors.AdminErrorCode;
import com.exchange.admin.bff.copy.dto.CopyLeaderViews.Page_;
import com.exchange.admin.bff.copy.dto.CopyLeaderViews.Row;
import com.exchange.copy.leader.CopyLeaderService;
import com.exchange.copy.leader.exception.LeaderNotFoundException;
import com.exchange.copy.leader.exception.LeaderStatusException;
import com.exchange.copy.follow.CopyFollowRepository;
import com.exchange.copy.follow.FollowStatus;
import com.exchange.copy.leader.CopyLeader;
import com.exchange.copy.leader.CopyLeaderRepository;
import com.exchange.copy.leader.LeaderStatus;
import com.exchange.core.api.exception.ApiException;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may lead, for staff (docs/reference/copy-trading.md). The rules are
 * {@link CopyLeaderService}'s; this translates its refusals into this app's codes and audits
 * each decision in the same transaction.
 *
 * <p>Suspending a leader changes a status and nothing else here. Its followers' copy accounts
 * are closed by the copier, which has the order path this app does not.
 */
@Service
public class CopyLeadersService {

    private static final int MAX_PAGE = 100;

    private final CopyLeaderService leaderService;
    private final CopyLeaderRepository leaders;
    private final CopyFollowRepository follows;
    private final UserRepository users;
    private final AdminAuditService audit;

    public CopyLeadersService(CopyLeaderService leaderService, CopyLeaderRepository leaders,
                              CopyFollowRepository follows, UserRepository users, AdminAuditService audit) {
        this.leaderService = leaderService;
        this.leaders = leaders;
        this.follows = follows;
        this.users = users;
        this.audit = audit;
    }

    /** Newest first. */
    public Page_ list(LeaderStatus status, int page, int size) {
        PageRequest paging = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE),
                Sort.by("createdAt").descending());
        Page<CopyLeader> found = status == null ? leaders.findAll(paging) : leaders.findByStatus(status, paging);
        Map<Long, User> owners = users.findAllById(found.map(CopyLeader::getUserId).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return new Page_(found.map(l -> row(l, owners.get(l.getUserId()))).toList(), found.getTotalElements(),
                found.getNumber(), found.getSize());
    }

    @Transactional
    public Row suspend(Long by, UUID id, String reason, String ip) {
        CopyLeader leader = refused(() -> leaderService.suspend(by, id, reason));
        audit.record(by, AdminAction.COPY_LEADER_SUSPENDED, "copy_leader", id.toString(), status(LeaderStatus.ACTIVE),
                because(LeaderStatus.SUSPENDED, reason), ip);
        return row(leader);
    }

    @Transactional
    public Row reinstate(Long by, UUID id, String ip) {
        CopyLeader leader = refused(() -> leaderService.reinstate(by, id));
        audit.record(by, AdminAction.COPY_LEADER_REINSTATED, "copy_leader", id.toString(),
                status(LeaderStatus.SUSPENDED), status(LeaderStatus.ACTIVE), ip);
        return row(leader);
    }

    /**
     * This leader's own share of their followers' profit, or back to the exchange's (null). It
     * applies to whoever follows from now on; an existing follow keeps the rate it started with.
     */
    @Transactional
    public Row setProfitShare(Long by, UUID id, Integer profitShareBps, String ip) {
        CopyLeader leader = leaders.findById(id).orElseThrow(() -> new ApiException(AdminErrorCode.COPY_LEADER_NOT_FOUND));
        String was = rate(leader.getProfitShareBps());
        leader.setProfitShareBps(profitShareBps);
        leaders.save(leader);
        audit.record(by, AdminAction.COPY_LEADER_PROFIT_SHARE_SET, "copy_leader", id.toString(), was,
                rate(profitShareBps), ip);
        return row(leader);
    }

    private static String rate(Integer bps) {
        return "{\"profitShareBps\":" + bps + "}";
    }

    private static CopyLeader refused(Supplier<CopyLeader> decision) {
        try {
            return decision.get();
        } catch (LeaderNotFoundException e) {
            throw new ApiException(AdminErrorCode.COPY_LEADER_NOT_FOUND);
        } catch (LeaderStatusException e) {
            throw new ApiException(AdminErrorCode.COPY_LEADER_WRONG_STATE);
        }
    }

    private Row row(CopyLeader leader) {
        return row(leader, users.findById(leader.getUserId()).orElse(null));
    }

    private Row row(CopyLeader l, User owner) {
        return new Row(l.getId(), l.getUserId(), owner == null ? null : owner.getEmail(), l.getDisplayName(), l.getBio(),
                l.getStatus(), follows.countByLeaderIdAndStatus(l.getId(), FollowStatus.ACTIVE), l.getLeadUserId(),
                l.getCreatedAt(), l.getReviewedAt(), l.getReviewReason(), l.getProfitShareBps());
    }

    private static String status(LeaderStatus status) {
        return "{\"status\":\"" + status + "\"}";
    }

    private static String because(LeaderStatus status, String reason) {
        return "{\"status\":\"" + status + "\",\"reason\":\""
                + reason.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }
}
