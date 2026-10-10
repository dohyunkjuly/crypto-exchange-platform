package com.exchange.spot.settlement.fee;

import com.exchange.common.money.Money;
import com.exchange.fee.FeeProduct;
import com.exchange.fee.volume.UserDailyVolume;
import com.exchange.fee.volume.UserDailyVolumeRepository;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.symbol.SpotSymbolRepository;
import com.exchange.spot.trade.SpotTrade;
import com.exchange.spot.trade.SpotTradeRepository;
import com.exchange.wallet.asset.AssetService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One UTC day of {@code spot_trades} becomes one {@code fee_user_daily_volumes} row per account
 * (docs/reference/fee-tiers.md).
 *
 * <p>Volume is measured in one asset, so a market quoted in anything else is converted at that
 * day's close -- the last trade of the day on the market that prices its quote in the volume
 * asset. Converting the day's total once, rather than each trade at its own price, is what the
 * published rule says and what a customer can check. A quote with no such market cannot be
 * valued, and its volume does not count: logged, not fatal, so one odd listing never stops
 * everyone else's tier.
 *
 * <p>The day is deleted and rewritten in one transaction, so a rerun is harmless and a trade
 * that settled after the first run is picked up by the next.
 */
@Service
public class VolumeRecountService {

    private static final Logger log = LoggerFactory.getLogger(VolumeRecountService.class);

    private final SpotTradeRepository trades;
    private final SpotSymbolRepository markets;
    private final AssetService assets;
    private final UserDailyVolumeRepository volumes;
    private final String volumeAsset;

    public VolumeRecountService(SpotTradeRepository trades, SpotSymbolRepository markets, AssetService assets,
                                UserDailyVolumeRepository volumes,
                                @Value("${exchange.fee.volume-asset}") String volumeAsset) {
        this.trades = trades;
        this.markets = markets;
        this.assets = assets;
        this.volumes = volumes;
        this.volumeAsset = volumeAsset;
    }

    /** @return how many accounts traded that day */
    @Transactional
    public int recount(LocalDate day) {
        Instant from = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Long volumeAssetId = assets.getBySymbol(volumeAsset).getId();

        Map<Long, long[]> perUser = new LinkedHashMap<>();
        for (SpotSymbol market : markets.findAll()) {
            Optional<Long> rate = rate(market, volumeAssetId, from, to);
            if (rate.isEmpty()) {
                continue;
            }
            for (List<Object[]> side : List.of(trades.buyerVolumeBetween(market.getId(), from, to),
                    trades.sellerVolumeBetween(market.getId(), from, to))) {
                for (Object[] row : side) {
                    long[] total = perUser.computeIfAbsent((Long) row[0], id -> new long[2]);
                    total[0] += Money.multiply(((Number) row[1]).longValue(), rate.get());
                    total[1] += ((Number) row[2]).longValue();
                }
            }
        }

        volumes.deleteByTradeDateAndProduct(day, FeeProduct.SPOT);
        List<UserDailyVolume> rows = new ArrayList<>(perUser.size());
        perUser.forEach((userId, total) ->
                rows.add(new UserDailyVolume(userId, day, FeeProduct.SPOT, total[0], total[1])));
        volumes.saveAll(rows);
        return rows.size();
    }

    /** Units of the volume asset per unit of the market's quote, 1e8-scaled; empty when it cannot be priced. */
    private Optional<Long> rate(SpotSymbol market, Long volumeAssetId, Instant from, Instant to) {
        if (market.getQuoteAssetId().equals(volumeAssetId)) {
            return Optional.of(Money.SCALE_FACTOR);
        }
        Optional<SpotSymbol> pricing = markets.findByBaseAssetIdAndQuoteAssetId(market.getQuoteAssetId(), volumeAssetId);
        Optional<Long> close = pricing.flatMap(p -> trades.lastBefore(p.getId(), to)).map(SpotTrade::getPrice);
        if (close.isEmpty() && !trades.buyerVolumeBetween(market.getId(), from, to).isEmpty()) {
            log.warn("{} is quoted in an asset with no {} price, so its volume counts towards no fee tier",
                    market.getSymbol(), volumeAsset);
        }
        return close;
    }
}
