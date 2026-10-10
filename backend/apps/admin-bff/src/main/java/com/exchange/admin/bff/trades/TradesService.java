package com.exchange.admin.bff.trades;

import com.exchange.admin.bff.common.lookup.NameLookups;
import com.exchange.admin.bff.common.lookup.NameLookups.Market;
import com.exchange.admin.bff.common.paging.Chunk;
import com.exchange.admin.bff.trades.dto.TradeViews.Party;
import com.exchange.admin.bff.trades.dto.TradeViews.Row;
import com.exchange.common.id.Ulid;
import com.exchange.common.money.Money;
import com.exchange.spot.trade.SpotTrade;
import com.exchange.spot.trade.SpotTradeRepository;
import com.exchange.wallet.asset.Asset;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

/**
 * Every trade, both sides of it: who bought from whom, at what price, and what each paid. For
 * support ("what did I trade") and for surveillance ("who traded with whom at that price").
 * Read only, like everything a trade touched.
 */
@Service
public class TradesService {

    /** Newest first; within a millisecond, by id, as MySQL orders BINARY(16). */
    private static final Comparator<SpotTrade> NEWEST = Comparator.comparing(SpotTrade::getExecutedAt)
            .thenComparing((a, b) -> Arrays.compareUnsigned(Ulid.toBytes(a.getId()), Ulid.toBytes(b.getId())))
            .reversed();

    private final SpotTradeRepository trades;
    private final NameLookups lookups;

    public TradesService(SpotTradeRepository trades, NameLookups lookups) {
        this.trades = trades;
        this.lookups = lookups;
    }

    /**
     * @param customer an email or a customer id; their trades on either side
     * @param from inclusive; {@code to} is exclusive
     */
    public Chunk<Row> list(String customer, String symbol, Instant from, Instant to, int page, int size) {
        PageRequest request = Chunk.request(page, size);
        // Named but unknown: an empty page, never every trade on the exchange.
        Optional<Long> userId = blank(customer) ? Optional.empty() : lookups.customer(customer);
        Optional<Long> symbolId = blank(symbol) ? Optional.empty() : lookups.market(symbol);
        if ((!blank(customer) && userId.isEmpty()) || (!blank(symbol) && symbolId.isEmpty())) {
            return Chunk.empty(request);
        }

        if (userId.isPresent()) {
            return ofCustomer(userId.get(), symbolId.orElse(null), from, to, request);
        }
        Slice<SpotTrade> found = trades.latest(symbolId.orElse(null), from == null ? null : Ulid.floor(from),
                to == null ? null : Ulid.floor(to), request);
        return Chunk.of(rows(found.getContent()), found);
    }

    /**
     * Each side read along its own index, as far as this page reaches plus one, then merged. A
     * deep page costs its depth, which a person paging by hand never reaches far into.
     */
    private Chunk<Row> ofCustomer(Long userId, Long symbolId, Instant from, Instant to, PageRequest request) {
        int reach = (int) request.getOffset() + request.getPageSize() + 1;
        PageRequest head = PageRequest.of(0, reach);
        List<SpotTrade> merged = new ArrayList<>(trades.boughtBy(userId, symbolId, from, to, head));
        merged.addAll(trades.soldBy(userId, symbolId, from, to, head));
        merged.sort(NEWEST);
        int start = (int) Math.min(request.getOffset(), merged.size());
        int end = Math.min(start + request.getPageSize(), merged.size());
        return new Chunk<>(rows(merged.subList(start, end)), request.getPageNumber(), request.getPageSize(),
                merged.size() > end);
    }

    private List<Row> rows(List<SpotTrade> page) {
        Map<Long, Market> markets = lookups.markets(page.stream().map(SpotTrade::getSymbolId).toList());
        Map<Long, String> emails = lookups.emails(page.stream()
                .flatMap(t -> Stream.of(t.getBuyerUserId(), t.getSellerUserId())).toList());
        Map<Long, Asset> assets = lookups.assets(page.stream()
                .flatMap(t -> Stream.of(t.getBuyerFeeAssetId(), t.getSellerFeeAssetId())).toList());
        return page.stream().map(t -> {
            Market market = markets.get(t.getSymbolId());
            return new Row(t.getId().toString(), market.symbol(), market.base(), market.quote(),
                    Money.toDecimal(t.getPrice()), Money.toDecimal(t.getQuantity()), Money.toDecimal(t.getQuoteQuantity()),
                    party(t.getBuyerUserId(), t.getBuyerOrderId().toString(), t.getBuyerFee(), t.getBuyerFeeAssetId(), emails, assets),
                    party(t.getSellerUserId(), t.getSellerOrderId().toString(), t.getSellerFee(), t.getSellerFeeAssetId(), emails, assets),
                    t.isBuyerIsMaker(), t.getExecutedAt());
        }).toList();
    }

    private static Party party(Long userId, String orderId, long fee, Long feeAssetId, Map<Long, String> emails,
                               Map<Long, Asset> assets) {
        Asset asset = feeAssetId == null ? null : assets.get(feeAssetId);
        return new Party(userId, emails.get(userId), orderId, Money.toDecimal(fee), asset == null ? null : asset.getSymbol());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
