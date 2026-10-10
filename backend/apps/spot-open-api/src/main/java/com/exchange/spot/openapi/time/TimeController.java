package com.exchange.spot.openapi.time;

import java.time.Clock;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The clock a client signs against. Public: a bot needs it before it can make a signed call. */
@RestController
public class TimeController {

    public record ServerTime(Instant serverTime) {
    }

    private final Clock clock;

    public TimeController(Clock clock) {
        this.clock = clock;
    }

    @GetMapping("/v1/time")
    public ServerTime time() {
        return new ServerTime(clock.instant());
    }
}
