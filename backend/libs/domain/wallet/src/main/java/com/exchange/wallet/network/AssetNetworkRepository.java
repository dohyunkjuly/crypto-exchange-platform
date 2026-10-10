package com.exchange.wallet.network;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetNetworkRepository extends JpaRepository<AssetNetwork, Long> {

    /** "Which chains can I withdraw USDT on?" */
    List<AssetNetwork> findByAssetId(Long assetId);

    /** The row a deposit or withdrawal resolves to. */
    Optional<AssetNetwork> findByAssetIdAndNetworkId(Long assetId, Long networkId);

    List<AssetNetwork> findByAssetIdAndWithdrawalEnabledTrue(Long assetId);
}
