package com.exchange.spot.symbol;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpotSymbolRepository extends JpaRepository<SpotSymbol, Long> {

    Optional<SpotSymbol> findBySymbol(String symbol);

    /** The markets list, and the engine's startup load. */
    List<SpotSymbol> findByStatus(SymbolStatus status);

    Optional<SpotSymbol> findByBaseAssetIdAndQuoteAssetId(Long baseAssetId, Long quoteAssetId);
}
