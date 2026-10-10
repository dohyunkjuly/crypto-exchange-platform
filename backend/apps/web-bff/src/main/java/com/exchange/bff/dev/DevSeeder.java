package com.exchange.bff.dev;

import com.exchange.common.crypto.Totp;
import com.exchange.user.apikey.ApiKeyService;
import com.exchange.user.apikey.dto.CreatedApiKey;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.kyc.KycLevel;
import com.exchange.user.kyc.KycService;
import com.exchange.user.mfa.MfaService;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetRepository;
import com.exchange.wallet.balance.BalanceService;
import com.exchange.wallet.ledger.ReferenceType;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dummy accounts for the local stack, created at startup under the dev profile only.
 *
 * <p>Not in db/seed, which holds the markets and the fee schedule: those are plain rows, and
 * these accounts need the services -- a bcrypt hash, an enrolled second factor, a minted API
 * key with its secret printed once. Living behind {@code @Profile("dev")}, they exist only where
 * that profile is on, which is the local compose stack and nowhere else.
 *
 * <p>Idempotent by email: a restart adds nothing, and {@code docker compose down -v} recreates
 * everything on the next start. The authenticator secrets are fixed (CONTRIBUTING lists them), so
 * an app set up once keeps working; the API secret is minted by the service and printed once.
 */
@Component
@Profile("dev")
public class DevSeeder {

    private static final Logger log = LoggerFactory.getLogger(DevSeeder.class);

    /** One password for all of them; long enough for registration's rule. */
    static final String PASSWORD = "dev-password";
    /** 1e8-scaled: 1,000,000 units of every asset -- enough to trade any market for a while. */
    private static final long BALANCE = 1_000_000L * 100_000_000L;

    /** What each account has, so the gates can be seen from every side. */
    /** {@code totpSecret} is fixed, so an authenticator app set up once keeps working across resets. */
    record Persona(String email, String name, boolean kyc, String totpSecret, boolean apiKey, boolean funded) {}

    static final List<Persona> PERSONAS = List.of(
            new Persona("alice@example.com", "Alice", true, "ALICEDEVSECRET2345ALICEDEVSECRET", true, true),  // everything, incl. an API key
            new Persona("bob@example.com", "Bob", true, "BOBDEVSECRET234567BOBDEVSECRET23", false, true),     // can trade, no key
            new Persona("carol@example.com", "Carol", false, null, false, true));                             // verified email only: sees the gates

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final KycService kyc;
    private final MfaService mfa;
    private final ApiKeyService apiKeys;
    private final BalanceService balances;
    private final AssetRepository assets;
    private final String issuer;

    public DevSeeder(UserRepository users, PasswordEncoder passwords, KycService kyc, MfaService mfa,
                     ApiKeyService apiKeys, BalanceService balances, AssetRepository assets,
                     @Value("${EXCHANGE_NAME}") String exchangeName) {
        this.issuer = exchangeName.trim();
        this.users = users;
        this.passwords = passwords;
        this.kyc = kyc;
        this.mfa = mfa;
        this.apiKeys = apiKeys;
        this.balances = balances;
        this.assets = assets;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        int created = 0;
        for (Persona p : PERSONAS) {
            if (users.findByEmail(p.email()).isEmpty()) {
                create(p);
                created++;
            }
        }
        log.warn("DEV PROFILE -- {} of {} seed accounts created; password for all: {}", created, PERSONAS.size(), PASSWORD);
    }

    /** One transaction per persona, so a failure leaves no half-made account to trip the next start. */
    @Transactional
    void create(Persona p) {
        User user = new User(p.email(), passwords.encode(PASSWORD));
        user.setName(p.name());
        user.setEmailVerified(true); // no inbox to read here
        user = users.save(user);
        Long id = user.getId();
        StringBuilder out = new StringBuilder("\n  ").append(p.email());

        if (p.kyc()) {
            kyc.approve(id, KycLevel.INTERMEDIATE, null);
        }
        if (p.totpSecret() != null) {
            String secret = mfa.startTotpEnrolment(id, p.email(), issuer, p.totpSecret()).secret();
            mfa.confirmTotpEnrolment(id, Totp.codeAt(secret, Instant.now()), "127.0.0.1");
            out.append("\n    authenticator secret: ").append(secret).append(" (fixed; see CONTRIBUTING)");
            if (p.apiKey()) {
                // The enrolment spent the current step; the next one is within the allowed drift.
                CreatedApiKey key = apiKeys.create(id, "dev", true, true, false, List.of(), null,
                        Totp.codeAt(secret, Instant.now().plusSeconds(30)), "127.0.0.1");
                out.append("\n    api key: ").append(key.key().getApiKey())
                   .append("\n    api secret: ").append(key.secret());
            }
        }
        if (p.funded()) {
            for (Asset asset : assets.findAll()) {
                balances.deposit(id, asset.getId(), BALANCE, ReferenceType.ADMIN,
                        "dev-seed", "dev-seed:" + p.email() + ":" + asset.getSymbol());
            }
            out.append("\n    funded with 1,000,000 of every asset");
        }
        log.warn("DEV PROFILE -- seeded account:{}", out);
    }
}
