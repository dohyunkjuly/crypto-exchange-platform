package com.exchange.admin.bff.trades;

import com.exchange.admin.bff.common.paging.Chunk;
import com.exchange.admin.bff.trades.dto.TradeViews.Row;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every trade, both sides of it, for support and surveillance. Read only. The work is
 * {@link TradesService}'s.
 */
@RestController
@RequestMapping("/admin/trades")
public class TradesController {

    private final TradesService service;

    public TradesController(TradesService service) {
        this.service = service;
    }

    /**
     * @param customer an email or a customer id; their trades on either side
     * @param from inclusive; {@code to} is exclusive
     */
    @GetMapping
    public Chunk<Row> list(@RequestParam(required = false) String customer, @RequestParam(required = false) String symbol,
                           @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                           @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return service.list(customer, symbol, from, to, page, size);
    }
}
