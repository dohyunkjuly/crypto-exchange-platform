package com.exchange.bff.dev;

import com.exchange.common.crypto.Totp;
import com.exchange.common.money.Money;
import com.exchange.core.api.error.CommonErrorCode;
import com.exchange.core.api.exception.ApiException;
import com.exchange.fee.FeeProduct;
import com.exchange.fee.override.UserFeeOverride;
import com.exchange.fee.override.UserFeeOverrideRepository;
import com.exchange.user.apikey.ApiKeyService;
import com.exchange.user.apikey.dto.CreatedApiKey;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.kyc.KycLevel;
import com.exchange.user.kyc.KycService;
import com.exchange.user.mfa.MfaService;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetService;
import com.exchange.wallet.balance.BalanceService;
import com.exchange.wallet.ledger.ReferenceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Accounts that can trade the moment they exist, for a local stack only.
 *
 * <p>Getting one account ready by hand is nine calls -- register, log in, read the emailed code,
 * verify, submit KYC, enrol an authenticator, confirm it, mint a key, fund it. That is the right
 * shape for a person and the wrong shape for a hundred bots, so this does all of it at once and
 * hands back the credentials, with fees set to zero. {@code bots/} decides how many and when;
 * this decides how.
 *
 * <p>Behind {@code @Profile("dev")} like {@link DevFundingController} and
 * {@link DevSeeder}. It skips the HTTP and email layers and drives the
 * domain services directly, so what it does not prove is that registration and verification work
 * over the wire -- that is one test of the signup flow's job, not a hundred bots'.
 *
 * <p><b>Every call creates new accounts.</b> It never reuses one, because an API secret is
 * returned once and stored encrypted, and minting a second key for an account keyed moments ago
 * is impossible anyway: an authenticator step is spent on use, and every code still inside the
 * drift window is gone with it. Whoever calls this decides whether it needs calling.
 *
 * <p>At {@code /dev}, not {@code /bff/dev} where the faucet is, and the difference is the point.
 * Everything under {@code /bff} needs a session, and a caller asking for its first account by
 * definition has none. Keeping it out of that namespace means the exclusion list in
 * {@code WebMvcConfig} -- production configuration -- says nothing about a dev endpoint.
 */
@RestController
@RequestMapping("/dev")
@Profile("dev")
public class DevAccountsController {

    private static final Logger log = LoggerFactory.getLogger(DevAccountsController.class);

    /** A batch is one request and one response; past this it is a denial of service on yourself. */
    private static final int MAX_BATCH = 500;

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final KycService kyc;
    private final MfaService mfa;
    private final ApiKeyService apiKeys;
    private final BalanceService balances;
    private final AssetService assets;
    private final UserFeeOverrideRepository feeOverrides;
    private final String issuer;

    public DevAccountsController(UserRepository users, PasswordEncoder passwords, KycService kyc,
                                 MfaService mfa, ApiKeyService apiKeys, BalanceService balances,
                                 AssetService assets, UserFeeOverrideRepository feeOverrides,
                                 @Value("${EXCHANGE_NAME}") String exchangeName) {
        this.issuer = exchangeName.trim();
        this.users = users;
        this.passwords = passwords;
        this.kyc = kyc;
        this.mfa = mfa;
        this.apiKeys = apiKeys;
        this.balances = balances;
        this.assets = assets;
        this.feeOverrides = feeOverrides;
    }

    /**
     * @throws ApiException {@link CommonErrorCode#VALIDATION_FAILED} the funding amount is not a
     *         decimal within {@link DevFundingController#MAX_PER_CALL}
     */
    @PostMapping("/accounts")
    public DevAccounts create(@Valid @RequestBody CreateAccountsRequest request) {
        long fund = fundingAmount(request.fund());
        List<Asset> tradable = assets.listTradable();
        // One hash for the whole batch. bcrypt at strength 12 is about a quarter of a second,
        // which would be half a minute of request for a hundred accounts sharing one password.
        String hash = passwords.encode(DevSeeder.PASSWORD);
        // Unique per call, so two batches never collide on an email.
        String run = UUID.randomUUID().toString().substring(0, 8);

        List<DevAccount> created = new ArrayList<>(request.count());
        for (int i = 1; i <= request.count(); i++) {
            created.add(create("%s-%s-%03d@example.com".formatted(request.label(), run, i), hash, tradable, fund));
        }
        log.warn("DEV PROFILE -- created {} accounts, each verified, KYC {}, 2FA on, keyed, fee-free "
                + "and funded with {} of every asset. This must never run in production.",
                created.size(), KycLevel.INTERMEDIATE, request.fund());
        return new DevAccounts(DevSeeder.PASSWORD, created);
    }

    /**
     * Not one transaction. Each account is new and its email is unique to this call, so a batch
     * that dies halfway leaves accounts nobody will ask for again rather than a half-made one
     * some later run has to finish.
     */
    private DevAccount create(String email, String passwordHash, List<Asset> tradable, long fund) {
        User user = new User(email, passwordHash);
        user.setName(email.substring(0, email.indexOf('@')));
        user.setEmailVerified(true); // there is no inbox to read here
        Long id = users.save(user).getId();

        kyc.approve(id, KycLevel.INTERMEDIATE, null);
        String secret = mfa.startTotpEnrolment(id, email, issuer).secret();
        mfa.confirmTotpEnrolment(id, Totp.codeAt(secret, Instant.now()), "127.0.0.1");

        for (Asset asset : tradable) {
            balances.deposit(id, asset.getId(), fund, ReferenceType.ADMIN, "dev-accounts",
                    "dev-accounts:" + id + ":" + asset.getSymbol());
        }
        // No fees: the bots trade with each other all day, and fees would only drain their
        // funding into the exchange, so nothing about their balances would mean anything.
        UserFeeOverride free = new UserFeeOverride(id, FeeProduct.SPOT, 0, 0);
        free.setReason("dev-accounts");
        feeOverrides.save(free);

        // The enrolment above spent the current step; the next one is within the allowed drift.
        CreatedApiKey key = apiKeys.create(id, "bot", true, true, false, List.of(), null,
                Totp.codeAt(secret, Instant.now().plusSeconds(30)), "127.0.0.1");
        return new DevAccount(email, id, key.key().getApiKey(), key.secret(), secret);
    }

    private static long fundingAmount(String fund) {
        long scaled;
        try {
            scaled = Money.toWire(fund);
        } catch (ArithmeticException | IllegalArgumentException e) {
            throw new ApiException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (scaled <= 0 || scaled > DevFundingController.MAX_PER_CALL) {
            throw new ApiException(CommonErrorCode.VALIDATION_FAILED);
        }
        return scaled;
    }

    /** {@code fund} is credited in every tradable asset, so an account can trade any market. */
    public record CreateAccountsRequest(@Min(1) @Max(MAX_BATCH) int count,
                                        @NotBlank @Size(max = 32) String label,
                                        @NotBlank String fund) {}

    /** Everything needed to trade as one of these, including the only copy of the API secret. */
    public record DevAccount(String email, Long userId, String apiKey, String apiSecret, String totpSecret) {}

    /** The password is shared by the batch, so any of them can also be logged into in a browser. */
    public record DevAccounts(String password, List<DevAccount> accounts) {}
}
