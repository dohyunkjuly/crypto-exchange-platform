package com.exchange.admin.bff.treasury;

import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Where the exchange's own money sits. The rows are created by a migration and never change,
 * so each id is resolved once and kept (docs/reference/treasury.md).
 */
@Component
public class HouseAccounts {

    private final UserRepository users;
    private final String feeEmail;
    private final String treasuryEmail;
    private volatile Long feeAccountId;
    private volatile Long treasuryAccountId;

    public HouseAccounts(UserRepository users,
                         @Value("${exchange.treasury.fee-account-email}") String feeEmail,
                         @Value("${exchange.treasury.treasury-account-email}") String treasuryEmail) {
        this.users = users;
        this.feeEmail = feeEmail;
        this.treasuryEmail = treasuryEmail;
    }

    public Long feeAccountId() {
        if (feeAccountId == null) {
            feeAccountId = idOf(feeEmail);
        }
        return feeAccountId;
    }

    public Long treasuryAccountId() {
        if (treasuryAccountId == null) {
            treasuryAccountId = idOf(treasuryEmail);
        }
        return treasuryAccountId;
    }

    private Long idOf(String email) {
        return users.findByEmail(email).map(User::getId).orElseThrow(
                () -> new IllegalStateException(email + " is missing; see V8__system_accounts.sql"));
    }
}
