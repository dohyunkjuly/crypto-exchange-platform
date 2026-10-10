package com.exchange.wallet.asset;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Asset id to symbol, held in memory.
 *
 * <p>Exists for the settlement stream. Messages on {@code exchange.spot.settlements.v1} carry
 * asset <em>ids</em> -- deliberately, because every producer holds ids and none holds symbols,
 * so naming them at the source would have put a query on the thread that had just released a
 * balance row lock. Somebody still has to do the translation, and the right place is the edge
 * that renders it.
 *
 * <p><b>Cached rather than queried per frame.</b> A frame arrives for every balance change of
 * every connected user, on the Kafka listener thread that is also delivering to every other
 * socket on this instance. A lookup there would be a database round trip in the fan-out path.
 * Assets change when an operator lists one, which is to say almost never, so a map refreshed
 * on a timer is both cheaper and no less correct.
 *
 * <p>{@code /bff/wallet/balances} deliberately does not use this: a REST request can afford
 * the query and gets a guaranteed-fresh answer, and the one place that cannot afford it is the
 * one that has to settle for a cache.
 */
@Component
public class AssetNames {

    private static final Logger log = LoggerFactory.getLogger(AssetNames.class);

    private final AssetRepository assets;

    /** Replaced wholesale, never mutated, so a reader sees one consistent map. */
    private volatile Map<Long, String> byId = Map.of();

    public AssetNames(AssetRepository assets) {
        this.assets = assets;
        // Eagerly: a frame arriving during startup must not find an empty map and render an
        // unnamed asset.
        refresh();
    }

    /** Empty for an id this instance has not seen -- a market listed since the last refresh.
     *  A caller should drop the frame rather than render a blank: the balance is still correct
     *  in MySQL, and the next refresh fixes the stream. */
    public Optional<String> of(long assetId) {
        return Optional.ofNullable(byId.get(assetId));
    }

    @Scheduled(fixedDelayString = "${exchange.wallet.asset-refresh-ms:60000}")
    public final void refresh() {
        try {
            byId = assets.findAll().stream()
                    .collect(Collectors.toUnmodifiableMap(Asset::getId, Asset::getSymbol));
        } catch (RuntimeException e) {
            // Keep the previous map: a stale asset name is almost certainly the right one,
            // and an empty map would leave every frame unnameable.
            log.warn("could not refresh asset names; serving the previous map", e);
        }
    }
}
