package com.exchange.spot.settlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Consumes {@code exchange.spot.events.v1} and is the one and only writer of wallet
 * balances and order status from it -- never web-bff, never spot-open-api, so a trade
 * can never be settled twice by two independent consumers of the same stream.
 *
 * <p>It has no database migrations of its own (owned centrally by {@code
 * libs:persistence}) and no HTTP endpoints for its actual job. The one exception is
 * {@code /actuator/health} (see application.yml's {@code server.port} comment, and
 * {@code health.ConsumerLivenessHealthIndicator}) -- a side door for a liveness probe
 * to tell a wedged consumer thread apart from an idle one, not a second public
 * interface. It is the only thing registered on that port.
 */
// com.exchange.wallet is listed explicitly because BalanceService is a @Service bean --
// @EntityScan/@EnableJpaRepositories below cover entities and repositories broadly, but
// not @Service beans, which need their package listed here or they fail to wire with
// "no qualifying bean," discoverable only by actually booting the app (hit and fixed
// twice already this session for web-bff, before this app existed to hit it a third
// time).
// Mongo is excluded: libs:domain:spot brings its starter in through marketdata, but
// settlement never touches it, and its health check would wait on a server that is not there.
@SpringBootApplication(
        scanBasePackages = {"com.exchange.spot.settlement", "com.exchange.core", "com.exchange.wallet",
                "com.exchange.user.identity", "com.exchange.fee"},
        exclude = {MongoAutoConfiguration.class, MongoDataAutoConfiguration.class,
                MongoRepositoriesAutoConfiguration.class})
@EntityScan("com.exchange")
@EnableJpaRepositories("com.exchange")
// The treasury and fee-tier jobs (treasury.TreasuryJobs, fee.FeeTierJobs); nothing on the
// settlement path is scheduled.
@EnableScheduling
public class SettlementApplication {

    public static void main(String[] args) {
        SpringApplication.run(SettlementApplication.class, args);
    }
}
