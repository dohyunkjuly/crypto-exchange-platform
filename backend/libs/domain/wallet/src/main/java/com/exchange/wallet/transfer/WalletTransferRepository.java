package com.exchange.wallet.transfer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletTransferRepository extends JpaRepository<WalletTransfer, UUID> {

    /** Everything a family moved, newest first: what the master asked for, and what touched any of its accounts. */
    @Query("SELECT t FROM WalletTransfer t WHERE t.requestedBy = :master OR t.fromUserId IN :family "
            + "OR t.toUserId IN :family ORDER BY t.createdAt DESC")
    List<WalletTransfer> history(@Param("master") Long master, @Param("family") List<Long> family, Pageable page);

    /** What moved into or out of one account in {@code (after, upTo]}. */
    @Query("SELECT t FROM WalletTransfer t WHERE (t.fromUserId = :userId OR t.toUserId = :userId) "
            + "AND t.createdAt > :after AND t.createdAt <= :upTo")
    List<WalletTransfer> touching(@Param("userId") Long userId, @Param("after") Instant after,
                                  @Param("upTo") Instant upTo);
}
