package com.exchange.user.kyc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserKycProfileRepository extends JpaRepository<UserKycProfile, Long> {

    Optional<UserKycProfile> findByUserId(Long userId);

    /** The compliance queue: what is waiting on review, oldest first. */
    Page<UserKycProfile> findByStatusOrderBySubmittedAtAsc(KycStatus status, Pageable pageable);

    /** The same queue once worked: what was decided, newest decision first. */
    Page<UserKycProfile> findByStatusOrderByReviewedAtDesc(KycStatus status, Pageable pageable);

    /** The re-verification sweep. */
    List<UserKycProfile> findByStatusAndExpiresAtBefore(KycStatus status, Instant before);
}
