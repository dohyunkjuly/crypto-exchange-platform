package com.exchange.user.eligibility;

import com.exchange.user.eligibility.exception.EmailNotVerifiedException;
import com.exchange.user.eligibility.exception.KycRequiredException;
import com.exchange.user.eligibility.exception.MfaRequiredException;
import com.exchange.user.eligibility.exception.TradingSuspendedException;
import com.exchange.user.eligibility.exception.AccountFrozenException;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserStatus;
import com.exchange.user.identity.UserType;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.kyc.KycLevel;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What an account may do, given how far it is verified and whether it has a second factor.
 *
 * <p>Reads the gate's read model on {@code user_users} -- {@code kyc_level},
 * {@code kyc_verified_until}, {@code mfa_enabled} (V1__user.sql) -- rather than joining the
 * profile and factor tables. Both apps already load the user to authenticate the request,
 * so {@link #requireCanTrade(User)} costs <b>no query at all</b>.
 *
 * <p>The exchange's own accounts are refused outright, before any of it: a house row is ACTIVE
 * and some later screen could give it a verified email and a second factor, so the type is what
 * refuses it rather than the state of its columns. docs/reference/treasury.md.
 *
 * <p>The tiers follow the usual split: <b>trading opens at {@link KycLevel#BASIC}</b>, while
 * <b>creating an API key requires {@link KycLevel#INTERMEDIATE}</b> -- their Verified Plus
 * rule, in force since 2021-08-09.
 *
 * <p>Throws plain, code-less exceptions per AGENTS.md: a shared service cannot see
 * either app's {@code ErrorCode}, so each app maps them to its own contract.
 */
@Service
public class EligibilityService {

    /** Enough to place an order: government ID and a selfie. */
    public static final KycLevel TRADING_LEVEL = KycLevel.BASIC;

    /**
     * Enough to mint an API key -- a higher bar than trading by hand.
     *
     * <p>Not arbitrary strictness: a key trades unattended, gets pasted into third-party
     * software, and outlives the session that made it. Every venue draws the line here.
     */
    public static final KycLevel API_KEY_LEVEL = KycLevel.INTERMEDIATE;

    private final UserRepository users;

    public EligibilityService(UserRepository users) {
        this.users = users;
    }

    /**
     * The trading gate, over a user the caller already holds -- no I/O.
     *
     * <p>Enforced at each app's edge rather than inside {@code OrderService}: web-bff's
     * interceptor has the user in hand, so this is free there, and placing an order is the
     * only thing it gates. <b>Cancelling and reading deliberately are not gated</b> --
     * someone whose verification lapsed must still be able to exit their positions, which
     * is the same reason a symbol can be {@code CANCEL_ONLY}.
     *
     * @throws EmailNotVerifiedException the address is unconfirmed
     * @throws KycRequiredException not verified far enough, or verification lapsed
     * @throws MfaRequiredException no active second factor
     * @throws AccountFrozenException the account is suspended, or is the exchange's own
     * @throws TradingSuspendedException an admin has suspended its trading, or its master's
     */
    public void requireCanTrade(User user) {
        User gated = gated(user);
        // Both rows must be ACTIVE: a frozen master's sub has its own status untouched, and
        // its key would otherwise keep trading through the freeze.
        if (user.getStatus() != UserStatus.ACTIVE || gated.getStatus() != UserStatus.ACTIVE
                || user.getType() == UserType.SYSTEM) {
            throw new AccountFrozenException();
        }
        requireNotSuspended(user, gated);
        require(gated, TRADING_LEVEL);
    }

    /**
     * Only the admin's trading suspension, for what else it stops: minting a trading key and
     * moving funds between sub-accounts, either of which would carry trading on around it.
     *
     * @throws TradingSuspendedException suspended on this row or on its master's
     */
    public void requireTradingNotSuspended(User user) {
        requireNotSuspended(user, gated(user));
    }

    /** By id, for the services that hold one. One primary-key read, plus the master's for a sub. */
    @Transactional(readOnly = true)
    public void requireTradingNotSuspended(Long userId) {
        requireTradingNotSuspended(users.findById(userId).orElseThrow(
                () -> new IllegalArgumentException("no such user: " + userId)));
    }

    /** A master's suspension covers its subs here, at the gate, rather than by writing to theirs. */
    private static void requireNotSuspended(User user, User gated) {
        if (user.isTradingSuspended() || gated.isTradingSuspended()) {
            throw new TradingSuspendedException();
        }
    }

    /** The same question without the exception, for a UI greying out a button rather than
     *  rejecting a request. */
    public boolean canTrade(User user) {
        User gated = gated(user);
        return user.getStatus() == UserStatus.ACTIVE && gated.getStatus() == UserStatus.ACTIVE
                && user.getType() != UserType.SYSTEM
                && !user.isTradingSuspended() && !gated.isTradingSuspended()
                && gated.isEmailVerified() && holdsLevel(gated, TRADING_LEVEL) && gated.isMfaEnabled();
    }

    /**
     * Whose verification counts. A sub-account has no email, KYC or second factor of its own:
     * its master's do (docs/reference/sub-accounts.md). The one case where this gate costs a
     * read, and only for subs, which are few.
     */
    private User gated(User user) {
        if (user.getType() != UserType.SUB || user.getParentUserId() == null) {
            return user;
        }
        return users.findById(user.getParentUserId()).orElse(user);
    }

    /**
     * The API-key gate, by id, because {@code ApiKeyService} works from a user id.
     *
     * <p>One indexed primary-key read, on a path that mints a credential once -- not a
     * path anything hammers. Kept in the domain rather than at the edge so open-api's
     * future key-management endpoint is correct without repeating the rule.
     *
     * @throws KycRequiredException not verified far enough, or verification lapsed
     * @throws MfaRequiredException no active second factor
     */
    @Transactional(readOnly = true)
    public void requireCanCreateApiKey(Long userId) {
        User user = users.findById(userId).orElseThrow(
                () -> new IllegalArgumentException("no such user: " + userId));
        require(user, API_KEY_LEVEL);
    }

    /**
     * Checked in the order a user would work through them, so whoever is blocked is told
     * about the earliest thing standing in their way rather than the last one checked.
     * Confirming an email is a click; identity verification takes minutes to days;
     * enrolling a factor sits in between.
     */
    private void require(User user, KycLevel required) {
        if (!user.isEmailVerified()) {
            throw new EmailNotVerifiedException();
        }
        if (!holdsLevel(user, required)) {
            throw new KycRequiredException(required);
        }
        if (!user.isMfaEnabled()) {
            throw new MfaRequiredException();
        }
    }

    /**
     * Level AND currency, never level alone.
     *
     * <p>{@code kycVerifiedUntil} is what makes a lapsed verification stop counting. KYC
     * expires at a wall-clock instant with no write happening anywhere, so a gate that
     * read only the level would keep letting someone trade months after theirs went stale.
     * Same shape as the {@code isEffective(now)} checks the fee and order-limit overrides
     * already use.
     */
    private boolean holdsLevel(User user, KycLevel required) {
        if (!user.getKycLevel().atLeast(required)) {
            return false;
        }
        Instant until = user.getKycVerifiedUntil();
        return until == null || until.isAfter(Instant.now());
    }
}
