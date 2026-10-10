package com.exchange.wallet.withdrawal;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WithdrawalRepository extends JpaRepository<Withdrawal, Long> {

    /** A retried request must return the original, not send twice. */
    Optional<Withdrawal> findByIdempotencyKey(String idempotencyKey);

    Page<Withdrawal> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /** The approval queue. */
    Page<Withdrawal> findByStatusOrderByRequestedAtAsc(WithdrawalStatus status, Pageable pageable);

    List<Withdrawal> findByStatusIn(List<WithdrawalStatus> statuses);

    Optional<Withdrawal> findByTxHash(String txHash);
}
