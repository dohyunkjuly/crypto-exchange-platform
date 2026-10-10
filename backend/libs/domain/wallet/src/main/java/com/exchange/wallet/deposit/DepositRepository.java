package com.exchange.wallet.deposit;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepositRepository extends JpaRepository<Deposit, Long> {

    /**
     * The duplicate check. A chain watcher restarts and replays; without this every
     * sighting of the same transaction would be a fresh credit.
     */
    Optional<Deposit> findByAssetNetworkIdAndTxHashAndTxOutputIndex(
            Long assetNetworkId, String txHash, int txOutputIndex);

    Page<Deposit> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /** The confirmation sweep: everything not yet buried deep enough. */
    List<Deposit> findByStatusIn(List<DepositStatus> statuses);

    List<Deposit> findByTxHash(String txHash);
}
