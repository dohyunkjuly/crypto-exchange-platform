package com.exchange.spot.openapi.subaccount.dto;

import com.exchange.user.identity.User;
import com.exchange.user.identity.UserStatus;
import java.time.Instant;

public record SubAccountView(Long subAccountId, String email, String label, boolean frozen, Instant createdAt) {

    public static SubAccountView of(User sub) {
        return new SubAccountView(sub.getId(), sub.getEmail(), sub.getName(),
                sub.getStatus() == UserStatus.SUSPENDED, sub.getCreatedAt());
    }
}
