package com.exchange.spot.openapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The public, documented, versioned API for spot. One surface, four groups of endpoints
 * — the shape a bot developer expects:
 *
 * <pre>
 *   /v1/account    who you are, API keys
 *   /v1/wallet     balances, deposits, withdrawals
 *   /v1/orders     order entry and cancellation
 *   /v1/markets    public market data
 * </pre>
 *
 * <p>Its defining property is that it is a <b>contract</b>. Once a trading bot depends on
 * {@code /v1/orders} the response cannot be casually reshaped -- it breaks their bot at 3am with
 * money on the line. So this app changes slowly and gets versioned when it must. That is exactly
 * why {@code web-bff} exists separately: the UI changes weekly and must not drag this along.
 *
 * <p>The {@code spot-} prefix is for what comes next: futures matching is a genuinely different
 * engine (mark price, margin, liquidation, position netting), so it gets its own app rather than
 * a pile of {@code if (isFutures)} branches here.
 *
 * <p>The annotations below point Spring at beans, entities and repositories that live in other
 * modules rather than under this package.
 */
// Every domain module whose @Service beans this app calls must be listed: @EntityScan and
// @EnableJpaRepositories below cover entities and repositories, but a service like
// ApiKeyService is found only by scanning its package -- a gap that has bitten three times.
@SpringBootApplication(scanBasePackages = {"com.exchange.spot.openapi", "com.exchange.core",
        "com.exchange.user", "com.exchange.wallet", "com.exchange.fee", "com.exchange.spot",
        // The order path's hook for lead and copy accounts: unscanned, their orders go unchecked.
        "com.exchange.marketdata", "com.exchange.copy"})
// The symbol catalog and the ticker cache refresh on a timer; without this they are read once
// at startup and never again, and a newly listed market never appears.
@EnableScheduling
@EntityScan("com.exchange")
@EnableJpaRepositories("com.exchange")
public class OpenApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpenApiApplication.class, args);
    }
}
