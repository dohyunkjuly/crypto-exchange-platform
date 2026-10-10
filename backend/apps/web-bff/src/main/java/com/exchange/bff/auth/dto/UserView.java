package com.exchange.bff.auth.dto;

import com.exchange.user.identity.User;

/** Never return {@link User} itself from a controller -- a column rename would become an
 *  API break, and passwordHash has no business leaving this process. */
public record UserView(Long id, String email, String name, boolean emailVerified,
                       String status) {

    public static UserView of(User user) {
        return new UserView(user.getId(), user.getEmail(), user.getName(),
                user.isEmailVerified(), user.getStatus().name());
    }
}
