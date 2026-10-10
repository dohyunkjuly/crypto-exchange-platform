package com.exchange.copy.settings;

import com.exchange.wallet.asset.AssetService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one asset copy trading is done in: what a copy account is funded with, and so the only
 * quote a lead account may trade (docs/reference/copy-trading.md). {@code exchange.copy.quote-asset}.
 */
@Component
public class CopyQuoteAsset {

    private final AssetService assets;
    private final String symbol;
    private volatile Long id;

    public CopyQuoteAsset(AssetService assets, @Value("${exchange.copy.quote-asset}") String symbol) {
        this.assets = assets;
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    /** Resolved once: an asset's id never changes. */
    public Long id() {
        Long resolved = id;
        if (resolved == null) {
            resolved = assets.getBySymbol(symbol).getId();
            id = resolved;
        }
        return resolved;
    }
}
