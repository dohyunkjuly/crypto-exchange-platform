package com.exchange.admin.bff;

import com.exchange.admin.bff.cli.CreateAdmin;
import java.io.IOException;
import java.util.Arrays;
import com.exchange.copy.leader.CopyLeaderService;
import com.exchange.fee.FeeQueryService;
import com.exchange.fee.tier.FeeTierService;
import com.exchange.spot.symbol.MarketListingService;
import com.exchange.user.apikey.ApiKeyRevocationService;
import com.exchange.user.kyc.KycService;
import com.exchange.user.subaccount.SubAccountService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Backend-for-frontend for {@code /admin}, staff only. Its own process, host, cookie and
 * login, none of them shared with what customers use: docs/reference/admin.md.
 *
 * <p>Scans {@code com.exchange.core.api} and not all of {@code com.exchange.core}: the rate
 * limiter there needs Redis, and a staff app on an internal network behind an IP allowlist
 * has no flood to limit. The exception handler and the response envelope are what it wants.
 *
 * <p>{@code java -jar admin-bff.jar create-admin --email ...} runs {@link CreateAdmin} instead
 * of the server: the same jar, so the first admin is made the same way on a laptop, a server
 * and in a container, and always from a real terminal that can hide the password.
 */
// com.exchange.wallet is listed for its @Service beans (AssetService and the treasury
// services), which a component scan finds and @EnableJpaRepositories does not; the entity and
// repository scans widen to com.exchange for the same reason web-bff's do.
// Mongo is excluded: libs:domain:spot brings its starter in through market data, and nothing here
// reads a candle -- its health check would wait on a server this app has no reason to reach.
@SpringBootApplication(scanBasePackages = {"com.exchange.admin", "com.exchange.core.api",
        // Two user services by class, not the package: ApiKeyService there needs the key-encryption
        // secret and the MFA service, neither of which this app has any business holding.
        "com.exchange.wallet"},
        exclude = {MongoAutoConfiguration.class, MongoDataAutoConfiguration.class,
                MongoRepositoriesAutoConfiguration.class})
@EntityScan("com.exchange")
@EnableJpaRepositories("com.exchange")
@Import({ApiKeyRevocationService.class, KycService.class, MarketListingService.class, FeeTierService.class,
        FeeQueryService.class,
        // Copy trading's leaders, and the service that makes an approved one's lead account.
        CopyLeaderService.class, SubAccountService.class})
public class AdminBffApplication {

    public static void main(String[] args) throws IOException {
        if (args.length > 0 && args[0].equals("create-admin")) {
            CreateAdmin.run(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        SpringApplication.run(AdminBffApplication.class, args);
    }
}
