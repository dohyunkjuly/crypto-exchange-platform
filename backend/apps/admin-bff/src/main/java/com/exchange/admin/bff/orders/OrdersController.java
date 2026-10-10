package com.exchange.admin.bff.orders;

import com.exchange.admin.bff.orders.dto.OrderViews.Detail;
import com.exchange.admin.bff.orders.dto.OrderViews.Page_;
import com.exchange.spot.order.Side;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every customer's orders, for support (docs/reference/admin.md). Read only: staff never cancel
 * or change a customer's order. The work is {@link OrdersService}'s.
 */
@RestController
@RequestMapping("/admin/orders")
public class OrdersController {

    private final OrdersService service;

    public OrdersController(OrdersService service) {
        this.service = service;
    }

    /**
     * @param customer an email or a customer id; a sub's orders are its own, found by its id
     * @param state {@code open}, {@code closed}, or one status by name; nothing means all
     */
    @GetMapping
    public Page_ list(@RequestParam(required = false) String customer, @RequestParam(required = false) String symbol,
                      @RequestParam(required = false) Side side, @RequestParam(required = false) String state,
                      @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return service.list(customer, symbol, side, state, from, to, page, size);
    }

    @GetMapping("/{id}")
    public Detail get(@PathVariable UUID id) {
        return service.get(id);
    }
}
