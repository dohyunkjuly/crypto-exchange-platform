package com.exchange.admin.bff.kyc;

import com.exchange.admin.audit.AdminAction;
import com.exchange.admin.bff.audit.AdminAuditService;
import com.exchange.admin.bff.common.errors.AdminErrorCode;
import com.exchange.admin.bff.kyc.dto.KycViews.Queue;
import com.exchange.admin.bff.kyc.dto.KycViews.Submission;
import com.exchange.core.api.exception.ApiException;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.identity.UserType;
import com.exchange.user.kyc.KycLevel;
import com.exchange.user.kyc.KycService;
import com.exchange.user.kyc.KycStatus;
import com.exchange.user.kyc.UserKycProfile;
import com.exchange.user.kyc.UserKycProfileRepository;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The compliance queue (docs/reference/admin.md): read a submission, approve or reject it, each
 * decision with its audit row in one transaction. What a customer typed is shown; documents are
 * not here and never will be -- the verification provider holds those under its own regime
 * ({@link KycService}).
 */
@Service
public class KycReviewService {

    private static final int MAX_PAGE = 100;

    private final UserKycProfileRepository profiles;
    private final UserRepository users;
    private final KycService kyc;
    private final AdminAuditService audit;

    public KycReviewService(UserKycProfileRepository profiles, UserRepository users, KycService kyc,
                            AdminAuditService audit) {
        this.profiles = profiles;
        this.users = users;
        this.kyc = kyc;
        this.audit = audit;
    }

    /** PENDING oldest first -- a queue is worked from the back; anything else newest decision first. */
    public Queue list(KycStatus status, int page, int size) {
        PageRequest paging = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE));
        Page<UserKycProfile> found = status == KycStatus.PENDING
                ? profiles.findByStatusOrderBySubmittedAtAsc(status, paging)
                : profiles.findByStatusOrderByReviewedAtDesc(status, paging);
        Map<Long, User> owners = users.findAllById(found.map(UserKycProfile::getUserId).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return new Queue(found.stream().filter(p -> owners.containsKey(p.getUserId()))
                .map(p -> Submission.of(p, owners.get(p.getUserId()))).toList(),
                found.getTotalElements(), found.getNumber(), found.getSize());
    }

    public Submission get(Long userId) {
        return Submission.of(kyc.statusOf(userId), customer(userId));
    }

    @Transactional
    public Submission approve(Long by, Long userId, KycLevel level, Instant expiresAt, String ip) {
        User user = customer(userId);
        if (level == KycLevel.NONE) {
            throw new ApiException(AdminErrorCode.INVALID_KYC_LEVEL);
        }
        UserKycProfile before = kyc.statusOf(userId);
        String was = "{\"status\":\"" + before.getStatus() + "\",\"level\":\"" + before.getLevel() + "\"}";
        kyc.approve(userId, level, expiresAt);
        audit.record(by, AdminAction.KYC_APPROVED, "user", userId.toString(), was,
                "{\"status\":\"APPROVED\",\"level\":\"" + level + "\",\"expiresAt\":"
                        + (expiresAt == null ? "null" : "\"" + expiresAt + "\"") + "}", ip);
        return Submission.of(kyc.statusOf(userId), user);
    }

    @Transactional
    public Submission reject(Long by, Long userId, String reason, String ip) {
        User user = customer(userId);
        UserKycProfile before = kyc.statusOf(userId);
        String was = "{\"status\":\"" + before.getStatus() + "\",\"level\":\"" + before.getLevel() + "\"}";
        kyc.reject(userId, reason);
        audit.record(by, AdminAction.KYC_REJECTED, "user", userId.toString(), was,
                "{\"status\":\"REJECTED\",\"reason\":\"" + reason.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}", ip);
        return Submission.of(kyc.statusOf(userId), user);
    }

    /** A primary account: a sub has no KYC of its own, and the house has none at all. */
    private User customer(Long userId) {
        return users.findById(userId)
                .filter(u -> u.getType() == UserType.PRIMARY)
                .orElseThrow(() -> new ApiException(AdminErrorCode.USER_NOT_FOUND));
    }
}
