package com.exchange.wallet.address;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepositAddressRepository extends JpaRepository<DepositAddress, Long> {

    /** The deposit screen. */
    Optional<DepositAddress> findByUserIdAndAssetNetworkIdAndIsActiveTrue(
            Long userId, Long assetNetworkId);

    List<DepositAddress> findByUserId(Long userId);

    /** The chain watcher resolving an incoming address back to a user. */
    Optional<DepositAddress> findByAssetNetworkIdAndAddressAndMemo(
            Long assetNetworkId, String address, String memo);

    /** Memo-routed chains share one address across users, so this can return several. */
    List<DepositAddress> findByAddress(String address);
}
