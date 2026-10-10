package com.exchange.spot.openapi.orders;

import com.exchange.spot.history.TimeRange;
import com.exchange.spot.openapi.common.ratelimit.CountsAsOrder;
import com.exchange.spot.openapi.common.ratelimit.Weight;
import com.exchange.spot.openapi.common.security.CurrentUser;
import com.exchange.spot.openapi.common.security.Permission;
import com.exchange.spot.openapi.common.security.RequiresPermission;
import com.exchange.spot.openapi.common.security.RequiresTrading;
import com.exchange.spot.openapi.orders.dto.AmendOrderRequest;
import com.exchange.spot.openapi.orders.dto.AmendOrderResponse;
import com.exchange.spot.openapi.orders.dto.CancelAllResponse;
import com.exchange.spot.openapi.orders.dto.CancelOrderResponse;
import com.exchange.spot.openapi.orders.dto.OrderView;
import com.exchange.spot.openapi.orders.dto.PlaceOrderRequest;
import com.exchange.spot.openapi.orders.dto.PlaceOrderResponse;
import com.exchange.spot.order.OpenOrderScope;
import com.exchange.spot.order.OrderQueryService;
import com.exchange.spot.order.OrderRequestFields;
import com.exchange.spot.order.OrderService;
import com.exchange.spot.order.OrderTimeField;
import com.exchange.spot.order.OrderType;
import com.exchange.spot.order.Side;
import com.exchange.spot.order.dto.OrderDetail;
import com.exchange.user.apikey.UserApiKey;
import com.exchange.user.identity.User;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /v1/orders}: entry, cancellation, and reads, over the same {@code OrderService} and
 * {@code OrderQueryService} web-bff uses, so both edges accept and refuse exactly the same orders.
 *
 * <p>Writes answer 202, never 200: an order and a cancel are durably queued commands, and the
 * engine decides the outcome. Placing needs the key's TRADE permission <b>and</b> the account
 * gate ({@code @RequiresTrading}); cancelling needs only the permission, and reading only READ --
 * a lapsed account must still be able to get out (AGENTS.md).
 */
@RestController
@RequestMapping("/v1/orders")
public class OrdersController {

    private static final int MAX_PAGE_SIZE = 500;
    private static final int DEFAULT_PAGE_SIZE = 100;

    private final OrderService orders;
    private final OrderQueryService queries;
    private final Clock clock;

    public OrdersController(OrderService orders, OrderQueryService queries, Clock clock) {
        this.orders = orders;
        this.queries = queries;
        this.clock = clock;
    }

    @PostMapping
    @RequiresPermission(Permission.TRADE)
    @RequiresTrading
    @CountsAsOrder
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PlaceOrderResponse place(@Valid @RequestBody PlaceOrderRequest r, @CurrentUser User user) {
        OrderDetail placed = orders.placeOrder(user.getId(), user.getParentUserId(), r.symbol(), OrderRequestFields.side(r.side()),
                OrderRequestFields.orderType(r.orderType()), OrderRequestFields.timeInForce(r.timeInForce()),
                r.price(), r.stopPrice(), r.quantity(), r.quoteOrderQty(), r.clientOrderId(),
                OrderRequestFields.selfTradePreventionMode(r.selfTradePreventionMode()), r.trailingDeltaBps());
        // The row the transaction committed, not a guess and not a second read: the status is NEW or
        // PENDING_TRIGGER by type, and a replayed clientOrderId returns an order that may have moved on.
        return PlaceOrderResponse.of(OrderView.of(placed));
    }

    /** Everything still capable of filling, newest first; {@code symbol} narrows it. */
    /** Three with a symbol, forty without: the unfiltered list is every open order on the account. */
    @GetMapping("/open")
    @Weight(3)
    public List<OrderView> open(@CurrentUser UserApiKey key, @RequestParam(required = false) String symbol) {
        return queries.openOrders(key.getUserId()).stream()
                .filter(d -> symbol == null || d.symbol().equals(symbol))
                .map(OrderView::of).toList();
    }

    /** Open and closed together, newest first. The window defaults to the last week and may
     *  span at most 90 days ({@code INVALID_TIME_RANGE}). */
    @GetMapping
    @Weight(10)
    public List<OrderView> history(@CurrentUser UserApiKey key,
                                   @RequestParam(required = false) String symbol,
                                   @RequestParam(required = false) Side side,
                                   @RequestParam(required = false) java.time.Instant startTime,
                                   @RequestParam(required = false) java.time.Instant endTime,
                                   @RequestParam(defaultValue = "CREATED_AT") OrderTimeField timeField,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        TimeRange range = TimeRange.of(startTime, endTime, clock.instant());
        return queries.history(key.getUserId(), symbol, side, range, timeField, Math.max(page, 0), clamp(size))
                .stream().map(OrderView::of).toList();
    }

    @GetMapping("/{orderId}")
    @Weight(2)
    public OrderView byId(@CurrentUser UserApiKey key, @PathVariable UUID orderId) {
        return OrderView.of(queries.byId(key.getUserId(), orderId));
    }

    /** Gated like placement, unlike cancel: an amend can add exposure. */
    @PatchMapping("/{orderId}")
    @RequiresPermission(Permission.TRADE)
    @RequiresTrading
    @CountsAsOrder
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AmendOrderResponse amend(@CurrentUser User user, @PathVariable UUID orderId,
                                    @RequestBody AmendOrderRequest r) {
        orders.amendOrder(user.getId(), orderId, r.price(), r.stopPrice(), r.quantity(), r.quoteOrderQty());
        return AmendOrderResponse.amending(orderId.toString());
    }

    @PatchMapping("/by-client-id/{clientOrderId}")
    @RequiresPermission(Permission.TRADE)
    @RequiresTrading
    @CountsAsOrder
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AmendOrderResponse amendByClientId(@CurrentUser User user, @PathVariable String clientOrderId,
                                              @RequestBody AmendOrderRequest r) {
        orders.amendOrderByClientId(user.getId(), clientOrderId, r.price(), r.stopPrice(), r.quantity(),
                r.quoteOrderQty());
        return AmendOrderResponse.amending(clientOrderId);
    }

    @DeleteMapping("/{orderId}")
    @RequiresPermission(Permission.TRADE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CancelOrderResponse cancel(@CurrentUser UserApiKey key, @PathVariable UUID orderId) {
        orders.cancelOrder(key.getUserId(), orderId);
        return CancelOrderResponse.canceling(orderId.toString());
    }

    /** By the id the client chose: after a timed-out placement it is the only id a bot holds. */
    @DeleteMapping("/by-client-id/{clientOrderId}")
    @RequiresPermission(Permission.TRADE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CancelOrderResponse cancelByClientId(@CurrentUser UserApiKey key, @PathVariable String clientOrderId) {
        orders.cancelOrderByClientId(key.getUserId(), clientOrderId);
        return CancelOrderResponse.canceling(clientOrderId);
    }

    /** Every open order matching the filters; the server picks them, never a client list of ids. */
    @DeleteMapping("/open")
    @RequiresPermission(Permission.TRADE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CancelAllResponse cancelAll(@CurrentUser UserApiKey key,
                                       @RequestParam(required = false) String symbol,
                                       @RequestParam(required = false) Side side,
                                       @RequestParam(required = false) OrderType type,
                                       @RequestParam(defaultValue = "ALL") OpenOrderScope scope) {
        return new CancelAllResponse(orders.cancelAllOrders(key.getUserId(), symbol, side, type, scope));
    }

    private static int clamp(int size) {
        return size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
    }
}
