package com.exchange.user.subaccount;

import com.exchange.user.audit.AuthEventType;
import com.exchange.user.audit.AuthOutcome;
import com.exchange.user.audit.UserAuthEvent;
import com.exchange.user.audit.UserAuthEventRepository;
import com.exchange.user.eligibility.exception.TradingSuspendedException;
import com.exchange.user.identity.SubPurpose;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.identity.UserStatus;
import com.exchange.user.identity.UserType;
import com.exchange.user.subaccount.exception.NotAMasterAccountException;
import com.exchange.user.subaccount.exception.SubAccountFrozenException;
import com.exchange.user.subaccount.exception.SubAccountLimitReachedException;
import com.exchange.user.subaccount.exception.SubAccountNotFoundException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A master's sub-accounts: separate balances, orders and keys under one identity, one level
 * deep. docs/reference/sub-accounts.md.
 *
 * <p>A sub is a {@code user_users} row of type SUB with no password: it cannot log in, and
 * everything about it is done by its master. Its trading gate is its master's, resolved by
 * {@code EligibilityService}. Freezing sets {@code SUSPENDED}, which the gate refuses.
 *
 * <p>A lead or a copy account (docs/reference/copy-trading.md) is a sub too, but not one of
 * these: it is made by {@link #createForCopyTrading}, does not count towards the limit, and
 * {@link #list} and {@link #get} never return it. So nothing a master does to an ordinary sub by
 * hand -- freeze it, mint it a key, move its funds, cancel its orders -- can reach one.
 *
 * <p>Throws plain, code-less exceptions per AGENTS.md; each app maps them to its own codes.
 */
@Service
public class SubAccountService {

    /** Enough to separate strategies, few enough that nobody farms accounts. */
    public static final int MAX_SUB_ACCOUNTS = 20;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final UserAuthEventRepository authEvents;

    public SubAccountService(UserRepository users, UserAuthEventRepository authEvents) {
        this.users = users;
        this.authEvents = authEvents;
    }

    /**
     * @param label what the master calls it; stored as the sub's name
     * @throws NotAMasterAccountException the caller is itself a sub
     * @throws SubAccountLimitReachedException the master already has the most allowed
     */
    @Transactional
    public User create(Long masterId, String label, String ipAddress) {
        master(masterId);
        if (list(masterId).size() >= MAX_SUB_ACCOUNTS) {
            throw new SubAccountLimitReachedException("a master account may have " + MAX_SUB_ACCOUNTS + " sub-accounts");
        }
        return save(masterId, SubPurpose.STANDARD, label, ipAddress);
    }

    /**
     * A lead or a copy account. How many a master may have is the caller's rule, not the twenty.
     *
     * @throws NotAMasterAccountException the caller is itself a sub
     */
    @Transactional
    public User createForCopyTrading(Long masterId, SubPurpose purpose, String label, String ipAddress) {
        if (purpose == SubPurpose.STANDARD) {
            throw new IllegalArgumentException("an ordinary sub-account is made by create");
        }
        master(masterId);
        return save(masterId, purpose, label, ipAddress);
    }

    private User save(Long masterId, SubPurpose purpose, String label, String ipAddress) {
        User sub = new User(virtualEmail(), null);
        sub.setName(label);
        sub.setType(UserType.SUB);
        sub.setSubPurpose(purpose);
        sub.setParentUserId(masterId);
        sub.setTradeGroupId(masterId);
        sub.setEmailVerified(true);
        sub = users.save(sub);
        record(masterId, AuthEventType.SUB_ACCOUNT_CREATED, sub.getId(), ipAddress);
        return sub;
    }

    /** The master's ordinary sub-accounts. */
    @Transactional(readOnly = true)
    public List<User> list(Long masterId) {
        master(masterId);
        return users.findByParentUserId(masterId).stream()
                .filter(sub -> sub.getSubPurpose() == SubPurpose.STANDARD).toList();
    }

    /** @throws SubAccountNotFoundException not this master's, or not an ordinary sub-account */
    @Transactional(readOnly = true)
    public User get(Long masterId, Long subId) {
        return users.findById(subId)
                .filter(u -> u.getType() == UserType.SUB && masterId.equals(u.getParentUserId())
                        && u.getSubPurpose() == SubPurpose.STANDARD)
                .orElseThrow(() -> new SubAccountNotFoundException("no sub-account " + subId));
    }

    /** Frozen: cannot trade, cannot be sent funds. Its open orders stay; cancelling is never gated. */
    @Transactional
    public User setFrozen(Long masterId, Long subId, boolean frozen, String ipAddress) {
        User sub = get(masterId, subId);
        sub.setStatus(frozen ? UserStatus.SUSPENDED : UserStatus.ACTIVE);
        record(masterId, frozen ? AuthEventType.SUB_ACCOUNT_FROZEN : AuthEventType.SUB_ACCOUNT_UNFROZEN, subId,
                ipAddress);
        return users.save(sub);
    }

    /**
     * The ids a transfer may run between, checked: each is the master or one of its subs, a
     * frozen sub can neither send nor receive, and nothing moves while trading is suspended on
     * the master or on either side -- otherwise a suspended sub's funds would simply move to a
     * sibling that still trades.
     *
     * @throws SubAccountNotFoundException a side is not in this family
     * @throws SubAccountFrozenException a side is frozen
     * @throws TradingSuspendedException the master or a side has trading suspended
     */
    @Transactional(readOnly = true)
    public void requireTransferable(Long masterId, Long fromUserId, Long toUserId) {
        if (master(masterId).isTradingSuspended()) {
            throw new TradingSuspendedException();
        }
        for (Long side : List.of(fromUserId, toUserId)) {
            if (side.equals(masterId)) {
                continue;
            }
            User sub = get(masterId, side);
            if (sub.getStatus() != UserStatus.ACTIVE) {
                throw new SubAccountFrozenException("sub-account " + side + " is frozen");
            }
            if (sub.isTradingSuspended()) {
                throw new TradingSuspendedException();
            }
        }
    }

    /** The master and every sub, lead and copy accounts included, for reads that span the family. */
    @Transactional(readOnly = true)
    public List<Long> familyIds(Long masterId) {
        List<Long> ids = new ArrayList<>(List.of(masterId));
        users.findByParentUserId(masterId).forEach(sub -> ids.add(sub.getId()));
        return ids;
    }

    /**
     * A virtual address, {@code abc_virtual@wv7vw8eln.com}: random on both sides,
     * so it is unique without asking anyone for one, and reaches nobody. It is a column
     * {@code user_users} requires and an identifier a support desk can quote, never a mailbox:
     * a sub has no password and no way to log in (docs/reference/sub-accounts.md), so nothing
     * is ever sent to it. Deriving it from the master's would instead publish whose it is,
     * and route mail to a person who never asked for it.
     */
    private static String virtualEmail() {
        return random(8) + "_virtual@" + random(10) + ".com";
    }

    /** Base-36 from fresh random bytes, so every character is drawn, not just the low ones. */
    private static String random(int length) {
        StringBuilder out = new StringBuilder(length);
        while (out.length() < length) {
            out.append(Long.toString(Math.abs(RANDOM.nextLong()), 36));
        }
        return out.substring(0, length);
    }

    private User master(Long masterId) {
        User master = users.findById(masterId).orElseThrow(() -> new IllegalArgumentException("no such user"));
        if (master.getType() != UserType.PRIMARY) {
            throw new NotAMasterAccountException("a sub-account cannot have sub-accounts");
        }
        return master;
    }

    private void record(Long masterId, AuthEventType type, Long subId, String ipAddress) {
        authEvents.save(UserAuthEvent.builder().userId(masterId).eventType(type).outcome(AuthOutcome.SUCCESS)
                .ipAddress(ipAddress).metadata("{\"subAccountId\":" + subId + "}").build());
    }
}
