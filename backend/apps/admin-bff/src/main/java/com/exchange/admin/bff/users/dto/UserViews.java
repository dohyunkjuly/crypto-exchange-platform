package com.exchange.admin.bff.users.dto;

import com.exchange.common.money.Money;
import com.exchange.user.apikey.UserApiKey;
import com.exchange.user.identity.User;
import com.exchange.user.session.UserSession;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.balance.Balance;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The customer screens' shapes. Money is decimal strings ({@link Money}); nothing is valued. */
public final class UserViews {

    private UserViews() {
    }

    /** One row of the list, and the identity block of the detail. */
    /** {@code tradingSuspendedAt} is this row's own suspension; a sub's master's shows on the master. */
    public record Summary(Long id, String email, String name, String type, String status, String frozenReason,
                          boolean frozenWithMaster, Instant tradingSuspendedAt, String tradingSuspendedReason,
                          String kycLevel, Instant kycVerifiedUntil, boolean mfaEnabled,
                          boolean emailVerified, Instant createdAt) {

        public static Summary of(User u) {
            return new Summary(u.getId(), u.getEmail(), u.getName(), u.getType().name(), u.getStatus().name(),
                    u.getFrozenReason(), u.isFrozenWithMaster(), u.getTradingSuspendedAt(),
                    u.getTradingSuspendedReason(), u.getKycLevel().name(), u.getKycVerifiedUntil(),
                    u.isMfaEnabled(), u.isEmailVerified(), u.getCreatedAt());
        }
    }

    public record Page(List<Summary> items, long total, int page, int size) {
    }

    public record BalanceLine(String asset, int displayDecimals, String available, String locked, String total) {

        public static BalanceLine of(Balance b, Asset a) {
            return new BalanceLine(a.getSymbol(), a.getDisplayDecimals(), Money.toDecimal(b.getAvailable()),
                    Money.toDecimal(b.getLocked()), Money.toDecimal(b.getTotal()));
        }
    }

    /** The key's public half only; the secret is encrypted at rest and never leaves the row. */
    public record KeyLine(UUID id, Long userId, String apiKey, String label, boolean canRead, boolean canTrade,
                          Instant lastUsedAt, Instant expiresAt, Instant createdAt, Instant revokedAt) {

        public static KeyLine of(UserApiKey k) {
            return new KeyLine(k.getId(), k.getUserId(), k.getApiKey(), k.getLabel(), k.isCanRead(),
                    k.isCanTrade(), k.getLastUsedAt(), k.getExpiresAt(), k.getCreatedAt(), k.getRevokedAt());
        }
    }

    /** What a session says about where it came from. Never the token, never its hash. */
    public record SessionLine(Long id, String ipAddress, String userAgent, String countryCode,
                              Instant createdAt, Instant lastSeenAt, Instant expiresAt) {

        public static SessionLine of(UserSession s) {
            return new SessionLine(s.getId(), s.getIpAddress(), s.getUserAgent(), s.getCountryCode(),
                    s.getCreatedAt(), s.getLastSeenAt(), s.getExpiresAt());
        }
    }

    /**
     * Everything the detail screen shows, in one response. Sub-accounts are here and only here:
     * a sub is found through its master, the way it is owned.
     */
    public record Detail(Summary user, Summary master, List<Summary> subAccounts, List<BalanceLine> balances,
                         List<KeyLine> apiKeys, List<SessionLine> sessions) {
    }

    /** What a freeze or thaw did, for the screen to say back. */
    public record Changed(Summary user, List<Long> subsAffected, int sessionsRevoked) {
    }

    /** Why, as the admin typed it: a freeze or a trading suspension. */
    public record ReasonRequest(@NotBlank @Size(max = 255) String reason) {
    }
}
