package com.exchange.bff.common.security;

import com.exchange.bff.common.errors.BffHttpErrorCode;
import com.exchange.copy.leader.CopyLeadAccountService;
import com.exchange.copy.leader.exception.NotALeaderException;
import com.exchange.core.api.exception.ApiException;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import org.springframework.stereotype.Component;

/**
 * Which account a signed-in customer is trading as (docs/reference/copy-trading.md).
 *
 * <p>A lead account never logs in: its owner trades it by hand from their own session, by
 * naming it on the request and on the private socket. It is the only account that can be
 * named. An ordinary sub-account trades through its keys, and a copy account is the copier's
 * alone -- so the name is a fixed word, never an id a client could point at someone else's
 * account.
 *
 * <p>The gate still reads the session's customer ({@link TradingEligibilityInterceptor}): a
 * sub-account's verification is its master's, so the answer is the same.
 */
@Component
public class TradingAccounts {

    /** The request header, and the socket's query parameter. */
    public static final String HEADER = "X-Trading-Account";
    public static final String PARAMETER = "account";
    private static final String LEAD = "lead";

    private final CopyLeadAccountService leadAccounts;
    private final UserRepository users;

    public TradingAccounts(CopyLeadAccountService leadAccounts, UserRepository users) {
        this.leadAccounts = leadAccounts;
        this.users = users;
    }

    /**
     * @param named what the request asked for; null or blank is the customer's own account
     * @throws ApiException {@link BffHttpErrorCode#NOT_A_LEADER} this customer has no lead
     *         account, or the name is not one this app knows
     */
    public User resolve(User signedIn, String named) {
        if (named == null || named.isBlank()) {
            return signedIn;
        }
        if (!LEAD.equals(named)) {
            throw new ApiException(BffHttpErrorCode.NOT_A_LEADER);
        }
        try {
            return users.findById(leadAccounts.accountId(signedIn.getId()))
                    .orElseThrow(() -> new ApiException(BffHttpErrorCode.NOT_A_LEADER));
        } catch (NotALeaderException e) {
            throw new ApiException(BffHttpErrorCode.NOT_A_LEADER);
        }
    }
}
