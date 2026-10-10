package com.exchange.admin.bff.markets;

import com.exchange.admin.audit.AdminAction;
import com.exchange.admin.bff.audit.AdminAuditService;
import com.exchange.admin.bff.common.errors.AdminErrorCode;
import com.exchange.admin.bff.markets.dto.MarketViews.AssetOption;
import com.exchange.admin.bff.markets.dto.MarketViews.CreateBody;
import com.exchange.admin.bff.markets.dto.MarketViews.LimitsBody;
import com.exchange.admin.bff.markets.dto.MarketViews.Listing;
import com.exchange.admin.bff.markets.dto.MarketViews.Row;
import com.exchange.common.money.Money;
import com.exchange.core.api.exception.ApiException;
import com.exchange.spot.symbol.MarketListingService;
import com.exchange.spot.symbol.MarketListingService.Limits;
import com.exchange.spot.symbol.MarketListingService.NewMarket;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.symbol.SpotSymbolRepository;
import com.exchange.spot.symbol.SymbolStatus;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Markets, for staff: list one, open it, move it between trading and cancel-only, and edit its
 * limits. The rules are {@link MarketListingService}'s; this translates its refusals into this
 * app's codes and writes each change's audit row in the same transaction.
 */
@Service
public class MarketsService {

    private final MarketListingService listing;
    private final SpotSymbolRepository markets;
    private final AssetRepository assets;
    private final AdminAuditService audit;
    private final int partitionCount;

    public MarketsService(MarketListingService listing, SpotSymbolRepository markets, AssetRepository assets,
                          AdminAuditService audit, @Value("${exchange.engine.partition-count}") int partitionCount) {
        this.listing = listing;
        this.markets = markets;
        this.assets = assets;
        this.audit = audit;
        this.partitionCount = partitionCount;
    }

    /** What the page needs: the markets, and what a new one may be made of. */
    public Listing list() {
        List<Asset> all = assets.findAll();
        Map<Long, Asset> byId = all.stream().collect(Collectors.toMap(Asset::getId, Function.identity()));
        return new Listing(
                markets.findAll().stream().sorted(Comparator.comparing(SpotSymbol::getSymbol))
                        .map(m -> row(m, byId)).toList(),
                all.stream().sorted(Comparator.comparing(Asset::getSymbol))
                        .map(a -> new AssetOption(a.getId(), a.getSymbol(), a.getName())).toList(),
                partitionCount);
    }

    public Row get(Long id) {
        return row(refused(() -> listing.market(id)));
    }

    /** Listed closed: it opens with the status call, so a mistake here never trades. */
    @Transactional
    public Row create(Long by, CreateBody body, String ip) {
        SpotSymbol market = refused(() -> listing.list(new NewMarket(body.baseAssetId(), body.quoteAssetId(),
                body.commandPartition(), Money.toWire(body.priceTick()), Money.toWire(body.qtyStep()),
                limits(body.limits())), partitionCount));
        Row row = row(market);
        audit.record(by, AdminAction.MARKET_LISTED, "market", market.getSymbol(), null,
                "{\"partition\":" + market.getCommandPartition() + ",\"priceTick\":\"" + row.priceTick()
                        + "\",\"qtyStep\":\"" + row.qtyStep() + "\"}", ip);
        return row;
    }

    @Transactional
    public Row setStatus(Long by, Long id, SymbolStatus status, String ip) {
        String was = refused(() -> listing.market(id)).getStatus().name();
        SpotSymbol market = refused(() -> listing.setStatus(id, status));
        audit.record(by, AdminAction.MARKET_STATUS_CHANGED, "market", market.getSymbol(),
                "{\"status\":\"" + was + "\"}", "{\"status\":\"" + market.getStatus() + "\"}", ip);
        return row(market);
    }

    @Transactional
    public Row setLimits(Long by, Long id, LimitsBody body, String ip) {
        String was = limitsJson(row(refused(() -> listing.market(id))).limits());
        SpotSymbol market = refused(() -> listing.setLimits(id, limits(body)));
        Row row = row(market);
        audit.record(by, AdminAction.MARKET_LIMITS_CHANGED, "market", market.getSymbol(), was,
                limitsJson(row.limits()), ip);
        return row;
    }

    /** The domain's plain refusals, as this app's codes. */
    private static <T> T refused(Supplier<T> call) {
        try {
            return call.get();
        } catch (MarketListingService.MarketNotFound e) {
            throw new ApiException(AdminErrorCode.MARKET_NOT_FOUND);
        } catch (IllegalStateException e) {
            throw new ApiException(e.getMessage().startsWith("already ")
                    ? AdminErrorCode.ALREADY_IN_THAT_STATE : AdminErrorCode.MARKET_ALREADY_LISTED);
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new ApiException(AdminErrorCode.INVALID_MARKET, e.getMessage());
        }
    }

    private static Limits limits(LimitsBody b) {
        return new Limits(Money.toWire(b.minQty()), Money.toWire(b.maxQty()), Money.toWire(b.minNotional()),
                wire(b.maxNotional()), wire(b.marketMinQty()), wire(b.marketMaxQty()), wire(b.minQuoteQty()),
                wire(b.maxQuoteQty()), b.priceBandBps(), b.maxOpenOrders(), b.maxOpenConditionalOrders(),
                b.marketOrdersEnabled(), b.stopOrdersEnabled());
    }

    private static Long wire(String decimal) {
        return decimal == null || decimal.isBlank() ? null : Money.toWire(decimal);
    }

    private static String decimal(Long wire) {
        return wire == null ? null : Money.toDecimal(wire);
    }

    private Row row(SpotSymbol m) {
        return row(m, assets.findAllById(List.of(m.getBaseAssetId(), m.getQuoteAssetId())).stream()
                .collect(Collectors.toMap(Asset::getId, Function.identity())));
    }

    private static Row row(SpotSymbol m, Map<Long, Asset> assets) {
        Asset base = assets.get(m.getBaseAssetId());
        Asset quote = assets.get(m.getQuoteAssetId());
        LimitsBody limits = new LimitsBody(Money.toDecimal(m.getMinQty()), Money.toDecimal(m.getMaxQty()),
                Money.toDecimal(m.getMinNotional()), decimal(m.getMaxNotional()), decimal(m.getMarketMinQty()),
                decimal(m.getMarketMaxQty()), decimal(m.getMinQuoteQty()), decimal(m.getMaxQuoteQty()),
                m.getPriceBandBps(), m.getMaxOpenOrders(), m.getMaxOpenConditionalOrders(),
                m.isMarketOrdersEnabled(), m.isStopOrdersEnabled());
        return new Row(m.getId(), m.getSymbol(), base == null ? null : base.getSymbol(),
                quote == null ? null : quote.getSymbol(), m.getStatus().name(), m.getCommandPartition(),
                Money.toDecimal(m.getPriceTick()), Money.toDecimal(m.getQtyStep()), limits, m.getCreatedAt());
    }

    private static String limitsJson(LimitsBody l) {
        return "{\"minQty\":\"" + l.minQty() + "\",\"maxQty\":\"" + l.maxQty() + "\",\"minNotional\":\""
                + l.minNotional() + "\",\"maxNotional\":" + quoted(l.maxNotional()) + ",\"marketMinQty\":"
                + quoted(l.marketMinQty()) + ",\"marketMaxQty\":" + quoted(l.marketMaxQty()) + ",\"minQuoteQty\":"
                + quoted(l.minQuoteQty()) + ",\"maxQuoteQty\":" + quoted(l.maxQuoteQty()) + ",\"priceBandBps\":"
                + l.priceBandBps() + ",\"maxOpenOrders\":" + l.maxOpenOrders() + ",\"maxOpenConditionalOrders\":"
                + l.maxOpenConditionalOrders() + ",\"marketOrdersEnabled\":" + l.marketOrdersEnabled()
                + ",\"stopOrdersEnabled\":" + l.stopOrdersEnabled() + "}";
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
