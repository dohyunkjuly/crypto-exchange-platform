package com.exchange.user.identity.external;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserExternalIdentityRepository extends JpaRepository<UserExternalIdentity, Long> {

    /** The sign-in lookup: by what the provider says this person is, never by email. */
    Optional<UserExternalIdentity> findByProviderAndSubject(ExternalProvider provider,
                                                           String subject);

    Optional<UserExternalIdentity> findByUserIdAndProvider(Long userId,
                                                          ExternalProvider provider);

    /** Everything linked to one account, for the settings screen. */
    List<UserExternalIdentity> findByUserId(Long userId);
}
