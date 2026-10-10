package com.exchange.admin.bff.users;

import com.exchange.admin.audit.AdminAction;
import com.exchange.admin.bff.audit.AdminAuditService;
import com.exchange.admin.bff.common.errors.AdminErrorCode;
import com.exchange.admin.bff.users.dto.UserViews;
import com.exchange.admin.bff.users.dto.UserViews.BalanceLine;
import com.exchange.admin.bff.users.dto.UserViews.Detail;
import com.exchange.admin.bff.users.dto.UserViews.KeyLine;
import com.exchange.admin.bff.users.dto.UserViews.SessionLine;
import com.exchange.admin.bff.users.dto.UserViews.Summary;
import com.exchange.core.api.exception.ApiException;
import com.exchange.user.apikey.ApiKeyRevocationService;
import com.exchange.user.apikey.ApiKeyRevokedReason;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.identity.UserStatus;
import com.exchange.user.identity.UserType;
import com.exchange.user.session.SessionRevokedReason;
import com.exchange.user.session.UserSessionRepository;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetService;
import com.exchange.wallet.balance.Balance;
import com.exchange.wallet.balance.BalanceService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What staff do to a customer: freeze, suspend trading, log out, revoke a key
 * (docs/reference/admin.md). Each write lands with its audit row in one transaction. Only this app
 * does these, so the rules live here; a master freezing its own sub from the web or the API is
 * {@code SubAccountService}'s, and the two agree on the flags below.
 *
 * <p><b>Freezing a master freezes its subs with it.</b> The trading gate already refuses a sub
 * whose master is SUSPENDED, so this changes nothing about what may trade -- it changes what the
 * rows say, so a sub does not read ACTIVE while it cannot. Unfreezing the master releases only the
 * subs it took down ({@code frozen_with_master}): one frozen deliberately beforehand stays frozen.
 * Sessions go with a freeze: a frozen account that keeps them is still reading its balances and
 * cancelling orders until they expire, which is not what "frozen" means to whoever asked.
 *
 * <p><b>Suspending trading stops trading and nothing else.</b> The account still logs in, reads and
 * cancels; the gate refuses new orders, amends, trading keys and sub-account transfers
 * ({@code EligibilityService}). Orders already on the book stay: staff never cancel a customer's
 * order. No sub row is written -- the gate reads the master for every sub.
 */
@Service
public class UsersService {

    /** What a freeze or thaw changed, for the answer and the audit row: the subs whose status moved. */
    public record Outcome(User user, List<Long> subsAffected, int sessionsRevoked) {
    }

    private static final int MAX_PAGE = 100;

    private final UserRepository users;
    private final UserSessionRepository sessions;
    private final ApiKeyRevocationService apiKeys;
    private final BalanceService balances;
    private final AssetService assets;
    private final AdminAuditService audit;

    public UsersService(UserRepository users, UserSessionRepository sessions, ApiKeyRevocationService apiKeys,
                        BalanceService balances, AssetService assets, AdminAuditService audit) {
        this.users = users;
        this.sessions = sessions;
        this.apiKeys = apiKeys;
        this.balances = balances;
        this.assets = assets;
        this.audit = audit;
    }

    /** Primary accounts, newest first; a sub is reached through its master's detail. {@code q} is an email or an id. */
    public UserViews.Page list(String q, UserStatus status, int page, int size) {
        String query = q == null || q.isBlank() ? null : q.trim();
        Long id = query != null && query.matches("\\d{1,18}") ? Long.parseLong(query) : -1L;
        Page<User> found = users.searchCustomers(query, id, status,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE)));
        return new UserViews.Page(found.map(Summary::of).getContent(), found.getTotalElements(),
                found.getNumber(), found.getSize());
    }

    /** One customer's file: who they are, their master or subs, balances, keys and sessions. */
    public Detail detail(Long id) {
        User user = customer(id);
        Summary master = user.getParentUserId() == null ? null
                : users.findById(user.getParentUserId()).map(Summary::of).orElse(null);
        List<User> subs = users.findByParentUserId(id);

        List<Balance> rows = balances.list(id);
        Map<Long, Asset> byId = assets.listByIds(rows.stream().map(Balance::getAssetId).toList())
                .stream().collect(Collectors.toMap(Asset::getId, Function.identity()));

        // The customer's own keys and every sub's: the keys a master answers for.
        List<Long> family = new ArrayList<>(subs.stream().map(User::getId).toList());
        family.add(id);

        return new Detail(Summary.of(user), master, subs.stream().map(Summary::of).toList(),
                rows.stream().map(b -> BalanceLine.of(b, byId.get(b.getAssetId()))).toList(),
                apiKeys.list(family).stream().map(KeyLine::of).toList(),
                sessions.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(id).stream().map(SessionLine::of).toList());
    }

    /** @throws ApiException USER_NOT_FOUND for a missing id or the exchange's own account */
    public User customer(Long id) {
        return users.findById(id)
                .filter(u -> u.getType() != UserType.SYSTEM)
                .orElseThrow(() -> new ApiException(AdminErrorCode.USER_NOT_FOUND));
    }

    @Transactional
    public Outcome freeze(Long by, Long userId, String reason, String ip) {
        User user = customer(userId);
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new ApiException(AdminErrorCode.ALREADY_IN_THAT_STATE);
        }
        user.setStatus(UserStatus.SUSPENDED);
        user.setFrozenReason(reason);
        user.setFrozenWithMaster(false);
        users.save(user);
        int revoked = sessions.revokeAllForUser(userId, SessionRevokedReason.REVOKED_BY_ADMIN, Instant.now());

        List<Long> subs = new ArrayList<>();
        if (user.getType() == UserType.PRIMARY) {
            for (User sub : users.findByParentUserId(userId)) {
                if (sub.getStatus() == UserStatus.SUSPENDED) {
                    continue; // frozen on its own; leave it, and leave it marked as such
                }
                sub.setStatus(UserStatus.SUSPENDED);
                sub.setFrozenReason(reason);
                sub.setFrozenWithMaster(true);
                users.save(sub);
                subs.add(sub.getId());
            }
        }
        audit.record(by, AdminAction.USER_FROZEN, "user", userId.toString(), "{\"status\":\"ACTIVE\"}",
                "{\"status\":\"SUSPENDED\",\"reason\":" + json(reason) + ",\"subs\":" + subs
                        + ",\"sessionsRevoked\":" + revoked + "}", ip);
        return new Outcome(user, subs, revoked);
    }

    @Transactional
    public Outcome unfreeze(Long by, Long userId, String ip) {
        User user = customer(userId);
        if (user.getStatus() != UserStatus.SUSPENDED) {
            throw new ApiException(AdminErrorCode.ALREADY_IN_THAT_STATE);
        }
        user.setStatus(UserStatus.ACTIVE);
        user.setFrozenReason(null);
        user.setFrozenWithMaster(false);
        users.save(user);

        List<Long> subs = new ArrayList<>();
        if (user.getType() == UserType.PRIMARY) {
            for (User sub : users.findByParentUserId(userId)) {
                if (!sub.isFrozenWithMaster()) {
                    continue; // frozen deliberately, on its own: not ours to release
                }
                sub.setStatus(UserStatus.ACTIVE);
                sub.setFrozenReason(null);
                sub.setFrozenWithMaster(false);
                users.save(sub);
                subs.add(sub.getId());
            }
        }
        audit.record(by, AdminAction.USER_UNFROZEN, "user", userId.toString(), "{\"status\":\"SUSPENDED\"}",
                "{\"status\":\"ACTIVE\",\"subs\":" + subs + "}", ip);
        return new Outcome(user, subs, 0);
    }

    @Transactional
    public User suspendTrading(Long by, Long userId, String reason, String ip) {
        User user = customer(userId);
        if (user.isTradingSuspended()) {
            throw new ApiException(AdminErrorCode.ALREADY_IN_THAT_STATE);
        }
        user.setTradingSuspendedAt(Instant.now());
        user.setTradingSuspendedReason(reason);
        users.save(user);
        audit.record(by, AdminAction.USER_TRADING_SUSPENDED, "user", userId.toString(), "{\"trading\":true}",
                "{\"trading\":false,\"reason\":" + json(reason) + "}", ip);
        return user;
    }

    @Transactional
    public User resumeTrading(Long by, Long userId, String ip) {
        User user = customer(userId);
        if (!user.isTradingSuspended()) {
            throw new ApiException(AdminErrorCode.ALREADY_IN_THAT_STATE);
        }
        user.setTradingSuspendedAt(null);
        user.setTradingSuspendedReason(null);
        users.save(user);
        audit.record(by, AdminAction.USER_TRADING_RESUMED, "user", userId.toString(), "{\"trading\":false}",
                "{\"trading\":true}", ip);
        return user;
    }

    /** Logout everywhere, without freezing: a stolen session, a shared laptop. */
    @Transactional
    public int revokeSessions(Long by, Long userId, String ip) {
        customer(userId);
        int revoked = sessions.revokeAllForUser(userId, SessionRevokedReason.REVOKED_BY_ADMIN, Instant.now());
        audit.record(by, AdminAction.USER_SESSIONS_REVOKED, "user", userId.toString(), null,
                "{\"sessionsRevoked\":" + revoked + "}", ip);
        return revoked;
    }

    /**
     * The key's owner may be the customer or one of their subs; the target recorded is the key.
     *
     * @param ownerId the account the key belongs to, or null for the customer themselves
     */
    @Transactional
    public void revokeApiKey(Long by, Long customerId, Long ownerId, UUID keyId, String ip) {
        customer(customerId);
        Long userId = ownerId == null ? customerId : ownerId;
        apiKeys.revoke(userId, keyId, ApiKeyRevokedReason.REVOKED_BY_ADMIN, ip);
        audit.record(by, AdminAction.USER_API_KEY_REVOKED, "api_key", keyId.toString(), null,
                "{\"userId\":" + userId + "}", ip);
    }

    /** A string field in the audit JSON, escaped the two ways free text needs. */
    private static String json(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
