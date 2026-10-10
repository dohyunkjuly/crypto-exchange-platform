package com.exchange.wallet.asset.exception;

/** No asset matches the given symbol or id. */
public class AssetNotFoundException extends RuntimeException {

    public AssetNotFoundException(Object identifier) {
        super("No such asset: " + identifier);
    }
}
