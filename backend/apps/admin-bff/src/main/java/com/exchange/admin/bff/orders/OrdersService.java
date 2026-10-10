package com.exchange.admin.bff.orders;

import com.exchange.admin.bff.common.errors.AdminErrorCode;
import com.exchange.admin.bff.common.lookup.NameLookups;
import com.exchange.admin.bff.common.lookup.NameLookups.Market;
import com.exchange.admin.bff.orders.dto.OrderViews.Detail;
import com.exchange.admin.bff.orders.dto.OrderViews.Fill;
import com.exchange.admin.bff.orders.dto.OrderViews.Page_;
import com.exchange.admin.bff.orders.dto.OrderViews.Row;
import com.exchange.common.money.Money;
import com.exchange.core.api.exception.ApiException;
import com.exchange.spot.order.OrderStatus;
import com.exchange.spot.order.Side;
import com.exchange.spot.order.SpotOrder;
import com.exchange.spot.order.SpotOrderRepository;
import com.exchange.spot.trade.SpotTrade;
import com.exchange.spot.trade.SpotTradeRepository;
import com.exchange.wallet.asset.Asset;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Every customer's orders, for support: "my order didn't fill" is answered here. Read only, on
 * purpose: an order is the customer's instruction, and staff never cancel or change one
 * (docs/reference/admin.md).
 */
@Service
public class OrdersService {

    private static final int MAX_PAGE = 100;
    private static final Set<OrderStatus> OPEN = EnumSet.of(OrderStatus.PENDING_TRIGGER, OrderStatus.NEW,
            OrderStatus.PARTIALLY_FILLED, OrderStatus.PENDING_CANCEL);

    private final SpotOrderRepository orders;
    private final SpotTradeRepository trades;
    private final NameLookups lookups;

    public OrdersService(SpotOrderRepository orders, SpotTradeRepository trades, NameLookups lookups) {
        this.orders = orders;
        this.trades = trades;
        this.lookups = lookups;
    }

    /**
     * @param customer an email or a customer id; a sub's orders are its own, found by its id
     * @param state {@code open}, {@code closed}, or one status by name; nothing means all
     */
    public Page_ list(String customer, String symbol, Side side, String state, Instant from, Instant to, int page,
                      int size) {
        PageRequest paging = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE));
        Page_ none = new Page_(List.of(), 0, paging.getPageNumber(), paging.getPageSize());

        // Named but unknown: an empty page, never every order on the exchange.
        Optional<Long> userId = blank(customer) ? Optional.empty() : lookups.customer(customer);
        if (!blank(customer) && userId.isEmpty()) {
            return none;
        }
        Optional<Long> symbolId = blank(symbol) ? Optional.empty() : lookups.market(symbol);
        if (!blank(symbol) && symbolId.isEmpty()) {
            return none;
        }
        Set<OrderStatus> statuses = statuses(state);
        Page<SpotOrder> found = orders.search(userId.orElse(null), symbolId.orElse(null), side, statuses == null,
                statuses == null ? Set.of(OrderStatus.NEW) : statuses, from, to, paging);
        return new Page_(rows(found.getContent()), found.getTotalElements(), found.getNumber(), found.getSize());
    }

    public Detail get(UUID id) {
        SpotOrder order = orders.findById(id).orElseThrow(() -> new ApiException(AdminErrorCode.ORDER_NOT_FOUND));
        boolean buy = order.getSide() == Side.BUY;
        List<SpotTrade> found = trades.findByBuyerOrderIdOrSellerOrderId(id, id);
        Map<Long, Asset> feeAssets = lookups.assets(found.stream()
                .map(t -> buy ? t.getBuyerFeeAssetId() : t.getSellerFeeAssetId()).toList());
        List<Fill> fills = found.stream()
                .sorted(Comparator.comparing(SpotTrade::getExecutedAt))
                .map(t -> {
                    Asset feeAsset = feeAssets.get(buy ? t.getBuyerFeeAssetId() : t.getSellerFeeAssetId());
                    return new Fill(t.getId().toString(), Money.toDecimal(t.getPrice()), Money.toDecimal(t.getQuantity()),
                            Money.toDecimal(t.getQuoteQuantity()), buy == t.isBuyerIsMaker(),
                            Money.toDecimal(buy ? t.getBuyerFee() : t.getSellerFee()),
                            feeAsset == null ? null : feeAsset.getSymbol(), t.getExecutedAt());
                })
                .toList();
        return new Detail(rows(List.of(order)).get(0), fills);
    }

    /** Null means every status. */
    private static Set<OrderStatus> statuses(String state) {
        if (blank(state)) {
            return null;
        }
        if (state.equalsIgnoreCase("open")) {
            return OPEN;
        }
        if (state.equalsIgnoreCase("closed")) {
            return Arrays.stream(OrderStatus.values()).filter(OrderStatus::isTerminal)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(OrderStatus.class)));
        }
        try {
            return EnumSet.of(OrderStatus.valueOf(state.toUpperCase()));
        } catch (IllegalArgumentException e) {
            throw new ApiException(AdminErrorCode.INVALID_ORDER_STATE);
        }
    }

    private List<Row> rows(List<SpotOrder> page) {
        Map<Long, Market> markets = lookups.markets(page.stream().map(SpotOrder::getSymbolId).toList());
        Map<Long, String> emails = lookups.emails(page.stream().map(SpotOrder::getUserId).toList());
        return page.stream().map(o -> {
            Market market = markets.get(o.getSymbolId());
            return new Row(o.getId().toString(), market.symbol(), market.base(), market.quote(), o.getUserId(),
                    emails.get(o.getUserId()), o.getSide().name(), o.getOrderType().name(), o.getStatus().name(),
                    decimal(o.getPrice()), decimal(o.getStopPrice()),
                    o.getQuoteOrderQty() != null && o.getQuantity() == 0 ? null : Money.toDecimal(o.getQuantity()),
                    decimal(o.getQuoteOrderQty()), Money.toDecimal(o.getFilledQuantity()),
                    Money.toDecimal(o.getCumulativeQuoteQuantity()), o.getClientOrderId(), o.getCreatedAt(), o.getUpdatedAt());
        }).toList();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String decimal(Long value) {
        return value == null ? null : Money.toDecimal(value);
    }
}
