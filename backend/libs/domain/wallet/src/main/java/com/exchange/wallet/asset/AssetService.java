package com.exchange.wallet.asset;

import com.exchange.wallet.asset.exception.AssetNotFoundException;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;

/** Shared by web-bff (wallet/market listings) and open-api (order validation needs to
 *  know an asset exists and is tradable) -- see the module's build file for why this
 *  lives here instead of in either app. */
@Service
public class AssetService {

    private final AssetRepository assets;

    public AssetService(AssetRepository assets) {
        this.assets = assets;
    }

    public List<Asset> listTradable() {
        return assets.findByTradingEnabledTrue();
    }

    /** Batched lookup for rendering a list of {@code Balance} rows, which only carry an
     *  {@code assetId} -- one query instead of one per row. */
    public List<Asset> listByIds(Collection<Long> ids) {
        return assets.findAllById(ids);
    }

    /** @throws AssetNotFoundException no asset has this id */
    public Asset getById(Long id) {
        return assets.findById(id).orElseThrow(() -> new AssetNotFoundException(id));
    }

    /** @throws AssetNotFoundException no asset has this symbol */
    public Asset getBySymbol(String symbol) {
        return assets.findBySymbol(symbol).orElseThrow(() -> new AssetNotFoundException(symbol));
    }
}
