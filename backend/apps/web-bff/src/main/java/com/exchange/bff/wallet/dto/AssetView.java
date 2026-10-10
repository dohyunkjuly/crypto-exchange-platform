package com.exchange.bff.wallet.dto;

import com.exchange.wallet.asset.Asset;

public record AssetView(String symbol, String name, String assetType, int displayDecimals) {

    public static AssetView of(Asset asset) {
        return new AssetView(asset.getSymbol(), asset.getName(), asset.getAssetType().name(),
                asset.getDisplayDecimals());
    }
}
