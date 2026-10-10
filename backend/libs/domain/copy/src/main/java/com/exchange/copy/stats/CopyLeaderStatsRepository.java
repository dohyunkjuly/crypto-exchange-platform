package com.exchange.copy.stats;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CopyLeaderStatsRepository extends JpaRepository<CopyLeaderStats, UUID> {
}
