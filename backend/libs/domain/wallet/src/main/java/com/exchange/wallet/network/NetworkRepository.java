package com.exchange.wallet.network;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NetworkRepository extends JpaRepository<Network, Long> {

    Optional<Network> findByCode(String code);

    List<Network> findByIsActiveTrue();
}
