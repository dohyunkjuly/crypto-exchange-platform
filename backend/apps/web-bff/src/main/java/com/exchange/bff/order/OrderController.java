package com.exchange.bff.order;

import com.exchange.bff.common.security.TradingAccount;
import com.exchange.bff.common.security.RequiresTrading;
import com.exchange.bff.order.dto.AmendOrderRequest;
import com.exchange.bff.order.dto.AmendOrderResponse;
import com.exchange.bff.order.dto.CancelAllResponse;
import com.exchange.bff.order.dto.CancelOrderResponse;
import com.exchange.bff.order.dto.OrderView;
import com.exchange.bff.order.dto.PlaceOrderRequest;
import com.exchange.bff.order.dto.PlaceOrderResponse;
import com.exchange.spot.history.TimeRange;
import com.exchange.spot.order.OrderQueryService;
import com.exchange.spot.order.OrderRequestFields;
import com.exchange.spot.order.OpenOrderScope;
import com.exchange.spot.order.OrderService;
import com.exchange.spot.order.OrderTimeField;
import com.exchange.spot.order.OrderType;
import com.exchange.spot.order.Side;
import com.exchange.spot.order.dto.OrderDetail;
import com.exchange.user.identity.User;
import jakarta.validation.Valid;
import java.time.Instant;
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
 * Placing, reading, amending and canceling this user's orders.
 *
 * <p>Every method is scoped to {@code @TradingAccount}: the signed-in customer's own
 * account, or their lead account when the request names it. There is no path here that
 * takes a user id from the request, so one user's order can never be read or canceled by
 * another. The read and cancel paths delegate to {@code libs/domain/spot}, which
 * enforces the same scoping again at the query itself.
 *
 * <p>Both writes answer 202, never 200. An order and a cancel are both durably queued
 * commands, not completed facts -- the engine decides the outcome and
 * {@code spot-settlement} writes it, so the row this endpoint would echo back is not
 * yet the answer.
 */
@RestController
@RequestMapping("/bff/orders")
public class OrderController {

    /** One screen of history. Deliberately modest -- the trading UI pages, and a client
     *  asking for ten thousand rows wants an export, which is a different endpoint. */
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final OrderService orderService;
    private final OrderQueryService orderQueryService;

    public OrderController(OrderService orderService, OrderQueryService orderQueryService) {
        this.orderService = orderService;
        this.orderQueryService = orderQueryService;
    }

    /** 202: durably queued, not yet matched -- the fill (or rejection) arrives later,
     *  once the settlement consumer exists. {@code OrderService} lives in
     *  {@code libs/domain/spot} (spot-open-api needs it too), so this just unpacks the
     *  request DTO into its plain-argument call, the same way {@code ApiKeyController}
     *  does for {@code ApiKeyService}. */
    @PostMapping
    @RequiresTrading
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PlaceOrderResponse place(@Valid @RequestBody PlaceOrderRequest request,
                                    @TradingAccount User user) {
        OrderDetail placed = orderService.placeOrder(user.getId(), user.getParentUserId(), request.symbol(),
                OrderRequestFields.side(request.side()),
                OrderRequestFields.orderType(request.orderType()),
                OrderRequestFields.timeInForce(request.timeInForce()), request.price(),
                request.stopPrice(), request.quantity(), request.quoteOrderQty(),
                request.clientOrderId(),
                OrderRequestFields.selfTradePreventionMode(request.selfTradePreventionMode()),
                request.trailingDeltaBps());
        return new PlaceOrderResponse(placed.order().getId().toString());
    }

    /** Everything still capable of filling, newest first -- the open-orders panel. */
    @GetMapping
    public List<OrderView> open(@TradingAccount User user) {
        return orderQueryService.openOrders(user.getId()).stream().map(OrderView::of).toList();
    }

    /** Open and closed together, newest first. Splitting them would make an order
     *  appear to vanish the moment it filled. {@code from}/{@code to} are ISO instants,
     *  defaulting to the last week; longer than 90 days is {@code INVALID_TIME_RANGE}. */
    @GetMapping("/history")
    public List<OrderView> history(@TradingAccount User user,
                                   @RequestParam(required = false) String symbol,
                                   @RequestParam(required = false) Side side,
                                   @RequestParam(required = false) Instant from,
                                   @RequestParam(required = false) Instant to,
                                   @RequestParam(defaultValue = "CREATED_AT") OrderTimeField timeField,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        TimeRange range = TimeRange.of(from, to, Instant.now());
        return orderQueryService.history(user.getId(), symbol, side, range, timeField,
                        Math.max(page, 0), clamp(size)).stream()
                .map(OrderView::of)
                .toList();
    }

    @GetMapping("/{orderId}")
    public OrderView byId(@TradingAccount User user, @PathVariable UUID orderId) {
        return OrderView.of(orderQueryService.byId(user.getId(), orderId));
    }

    /**
     * 202: the cancel is queued, not applied. Deliberately does not return the order --
     * at this instant the row still says NEW, and echoing it back would read as "the
     * cancel failed". Poll {@link #byId} or the open-orders list for the outcome.
     */
    /** 202, like placing. Gated like placing too, unlike cancel: an amend can add exposure. */
    @PatchMapping("/{orderId}")
    @RequiresTrading
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AmendOrderResponse amend(@TradingAccount User user, @PathVariable UUID orderId,
                                    @RequestBody AmendOrderRequest request) {
        orderService.amendOrder(user.getId(), orderId, request.price(), request.stopPrice(),
                request.quantity(), request.quoteOrderQty());
        return AmendOrderResponse.amending(orderId.toString());
    }

    @DeleteMapping("/{orderId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CancelOrderResponse cancel(@TradingAccount User user, @PathVariable UUID orderId) {
        orderService.cancelOrder(user.getId(), orderId);
        return new CancelOrderResponse(orderId.toString());
    }

    /** Every open order matching the filters -- all of them when none are given. 202 for the
     *  same reason as a single cancel, and ungated like it: a lapsed account must still be able
     *  to get out. The server picks the orders; it never takes a list of ids from the client. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CancelAllResponse cancelAll(@TradingAccount User user,
                                       @RequestParam(required = false) String symbol,
                                       @RequestParam(required = false) Side side,
                                       @RequestParam(required = false) OrderType type,
                                       @RequestParam(defaultValue = "ALL") OpenOrderScope scope) {
        return new CancelAllResponse(
                orderService.cancelAllOrders(user.getId(), symbol, side, type, scope));
    }

    private static int clamp(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
