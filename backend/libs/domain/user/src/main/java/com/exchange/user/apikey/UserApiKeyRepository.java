package com.exchange.user.apikey;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserApiKeyRepository extends JpaRepository<UserApiKey, UUID> {

    /** The lookup on every authenticated API request. */
    Optional<UserApiKey> findByApiKey(String apiKey);

    List<UserApiKey> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** A master's sub-accounts at once, so one screen of their keys is one query rather than one each. */
    List<UserApiKey> findByUserIdInOrderByCreatedAtDesc(Collection<Long> userIds);

    List<UserApiKey> findByUserIdAndRevokedAtIsNull(Long userId);

    /** One column, no entity load: the auth path calls this and must not pay for a read. */
    @Modifying
    @Query("update UserApiKey k set k.lastUsedAt = :at where k.id = :id")
    void touchLastUsed(@Param("id") UUID id, @Param("at") Instant at);
}
