package com.exchange.spot.limit.exchange;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpotExchangeOrderLimitsRepository
        extends JpaRepository<SpotExchangeOrderLimits, Integer> {
}
