package com.exchange.spot.order;

import com.exchange.spot.history.TimeRange;
import com.exchange.spot.order.dto.OrderDetail;
import com.exchange.spot.order.exception.OrderNotFoundException;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.symbol.SpotSymbolRepository;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading orders back. Separate from {@link OrderService}, which is entirely about
 * placing them: this class touches no balances, publishes no commands, and runs
 * read-only -- sharing a class with the write path would mean sharing its transaction
 * semantics for no reason.
 *
 * <p>Lives in {@code libs/domain/spot} rather than in web-bff because spot-open-api
 * needs the identical queries for its own {@code /v1/openOrders} and
 * {@code /v1/allOrders} -- the "does more than one app need it?" test in
 * AGENTS.md. It therefore throws plain exceptions with no error code attached;
 * each app maps them to its own contract.
 *
 * <p><b>Every query is scoped by {@code userId}.</b> There is no method here that can
 * return another user's order, by construction rather than by the caller remembering to
 * filter -- which is why {@link #byId} takes a user id it does not strictly need to find
 * the row.
 */
@Service
@Transactional(readOnly = true)
public class OrderQueryService {

    /**
     * What "open" means: still live in the engine, in some form.
     *
     * <p>{@code PENDING_TRIGGER} counts -- a stop order waiting for its trigger price is
     * not resting on the book, but the user placed it, it holds their funds locked, and
     * they must be able to see and cancel it. Leaving it out would make locked balance
     * unexplainable from the order list. {@code PENDING_CANCEL} counts for the same
     * reason: the cancel has been asked for but the engine has not confirmed it, so the
     * order is still capable of filling.
     */
    private static final List<OrderStatus> OPEN_STATUSES = List.of(
            OrderStatus.PENDING_TRIGGER, OrderStatus.NEW,
            OrderStatus.PARTIALLY_FILLED, OrderStatus.PENDING_CANCEL);

    private final SpotOrderRepository spotOrders;
    private final SpotSymbolRepository spotSymbols;

    public OrderQueryService(SpotOrderRepository spotOrders, SpotSymbolRepository spotSymbols) {
        this.spotOrders = spotOrders;
        this.spotSymbols = spotSymbols;
    }

    /** Every order of this user's that can still fill, newest first. */
    public List<OrderDetail> openOrders(Long userId) {
        // The repository method has no ordering of its own -- unlike the history query,
        // which is sorted in the database. Sorted here rather than by adding a second
        // repository method, because an open-order list is bounded by the account's
        // open-order cap (a few hundred at most), not by history.
        return withSymbols(spotOrders.findByUserIdAndStatusIn(userId, OPEN_STATUSES).stream()
                .sorted(Comparator.comparing(SpotOrder::getCreatedAt).reversed())
                .toList());
    }

    /**
     * Order history, newest first, including the open ones -- a user looking at "my
     * orders" means all of them, and splitting the two would make an order appear to
     * vanish the instant it filled.
     *
     * <p>{@code range} and the ordering both apply to {@code timeField}. A null
     * {@code symbol} or {@code side} means all; an unknown symbol is an empty page, as in
     * {@code TradeQueryService#history}.
     *
     * @param page zero-based
     */
    public List<OrderDetail> history(Long userId, String symbol, Side side, TimeRange range,
                                     OrderTimeField timeField, int page, int size) {
        Long symbolId = null;
        if (symbol != null && !symbol.isBlank()) {
            symbolId = spotSymbols.findBySymbol(symbol).map(SpotSymbol::getId).orElse(null);
            if (symbolId == null) {
                return List.of();
            }
        }
        PageRequest request = PageRequest.of(page, size);
        return withSymbols(timeField == OrderTimeField.UPDATED_AT
                ? spotOrders.findHistoryByUpdatedAt(userId, symbolId, side, range.from(), range.to(), request)
                : spotOrders.findHistoryByCreatedAt(userId, symbolId, side, range.from(), range.to(), request));
    }

    /** @throws OrderNotFoundException if no such order exists <b>or</b> it is not this
     *          user's -- see that exception for why those are one answer. */
    public OrderDetail byId(Long userId, UUID orderId) {
        SpotOrder order = spotOrders.findById(orderId)
                .filter(o -> o.getUserId().equals(userId))
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        return new OrderDetail(order, symbolCode(order.getSymbolId()));
    }

    /** One query for every distinct symbol in the page, not one per order. */
    private List<OrderDetail> withSymbols(List<SpotOrder> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }
        Set<Long> symbolIds = new HashSet<>();
        for (SpotOrder order : orders) {
            symbolIds.add(order.getSymbolId());
        }
        Map<Long, String> codes = codesById(symbolIds);
        return orders.stream()
                .map(order -> new OrderDetail(order, codes.get(order.getSymbolId())))
                .toList();
    }

    private Map<Long, String> codesById(Collection<Long> symbolIds) {
        return spotSymbols.findAllById(symbolIds).stream()
                .collect(Collectors.toMap(SpotSymbol::getId, SpotSymbol::getSymbol));
    }

    /** A symbol row is never deleted, so a missing one means the order references a
     *  symbol that no longer exists -- surface it as null rather than throwing, since
     *  refusing to show the order at all would be worse for the user than showing it
     *  without a name. */
    private String symbolCode(Long symbolId) {
        return spotSymbols.findById(symbolId).map(SpotSymbol::getSymbol).orElse(null);
    }
}
