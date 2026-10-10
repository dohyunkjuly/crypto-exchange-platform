package com.exchange.wallet.allowlist;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AllowlistedAddressRepository extends JpaRepository<AllowlistedAddress, Long> {

    /** The withdrawal check: is this destination allowed for this user. */
    Optional<AllowlistedAddress> findByUserIdAndAssetNetworkIdAndAddressAndMemo(
            Long userId, Long assetNetworkId, String address, String memo);

    List<AllowlistedAddress> findByUserIdAndStatus(Long userId, AllowlistStatus status);

    /** The sweep that promotes PENDING to ACTIVE once the cooling period has passed. */
    List<AllowlistedAddress> findByStatusAndActivatesAtBefore(AllowlistStatus status, Instant before);
}
