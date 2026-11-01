package com.exchange.copy.position;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CopyPositionRepository extends JpaRepository<CopyPosition, Long> {

    Optional<CopyPosition> findByFollowIdAndAssetId(UUID followId, Long assetId);
}
