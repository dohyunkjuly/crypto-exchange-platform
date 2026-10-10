package com.exchange.bff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Backend-for-frontend. It has exactly one client: the Next.js app in {@code /web}.
 *
 * <p>Because nobody else is allowed to depend on it, it is free to do the thing a
 * public API must never do — change shape whenever the UI changes, and return
 * exactly what one screen needs in one round trip. The trading page needs the last
 * price, the recent trades and the market status; {@code /bff/markets/BTC-USD}
 * returns all three together, so the browser makes one call instead of three.
 *
 * <p>If this logic lived in open-api instead, you would face a bad choice every time
 * the UI changed: either bloat the public contract with UI-shaped endpoints, or make
 * the browser fan out and stitch responses together itself. The BFF is what lets the
 * public contract stay clean AND the UI stay fast.
 */
// Every com.exchange.* package here besides com.exchange.bff itself holds a @Service this
// app consumes but doesn't own: ApiKeyService (com.exchange.user), BalanceService/
// AssetService (com.exchange.wallet), OrderService (com.exchange.spot -- shared with
// spot-open-api, see that module's build file). AuthService needs no such entry -- it
// lives entirely under com.exchange.bff, since open-api has no login flow to share it
// with. A module that only exposes repositories (e.g. com.exchange.fee) needs no entry
// here at all -- @EnableJpaRepositories below is already scoped to all of com.exchange.
@SpringBootApplication(
        scanBasePackages = {"com.exchange.bff", "com.exchange.core", "com.exchange.user",
                "com.exchange.wallet", "com.exchange.spot", "com.exchange.marketdata",
                // com.exchange.fee needs listing even though its repositories did not:
                // @EnableJpaRepositories below already covers all of com.exchange, but a
                // @Service is only found by the component scan. FeeQueryService is the
                // first service in that module, and its absence fails at startup rather
                // than at compile time.
                "com.exchange.fee",
                // The order path's hook for lead and copy accounts: unscanned, their orders
                // go unchecked.
                "com.exchange.copy"})
@EntityScan("com.exchange")
@EnableJpaRepositories("com.exchange")
public class WebBffApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebBffApplication.class, args);
    }
}
