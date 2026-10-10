package com.exchange.copy.leadorder;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CopyLeadOrderRepository extends JpaRepository<CopyLeadOrder, UUID> {
}
