package com.exchange.bff.dev;

import com.exchange.bff.wallet.dto.BalanceView;
import com.exchange.common.money.Money;
import com.exchange.core.api.error.CommonErrorCode;
import com.exchange.core.api.exception.ApiException;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetService;
import com.exchange.wallet.balance.Balance;
import com.exchange.wallet.balance.BalanceService;
import com.exchange.wallet.ledger.ReferenceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Money from nowhere, for a local stack only.
 *
 * <p>Nothing else in this codebase can put funds in an account. That is deliberate as far as it
 * goes -- deposits and withdrawals touch a blockchain and this is white-label
 * (docs/reference/white-label.md) -- but it leaves no way at all to set up an account that can
 * trade, which is what {@code bots/} and anyone evaluating the project both need first.
 *
 * <p>Behind {@code @Profile("dev")}, the same fence {@link DevSeeder}
 * stands behind, and for the same reason: it hands out something a real deployment sells.
 *
 * <p>Topping up an account that already exists; {@link DevAccountsController} funds the ones it
 * makes. Named by email rather than taken from a session: every account that endpoint creates
 * has a second factor, so logging one in is a two-phase MFA flow, and a bot holding an API key
 * has no session to offer at all. Nothing is lost by it -- an endpoint that mints funded
 * accounts on request is already standing next to this one.
 *
 * <p>The real thing this stands in for is an operator credit path: an exchange whose custody
 * system has seen a deposit needs to say so. That endpoint does not exist yet.
 */
@RestController
@RequestMapping("/dev")
@Profile("dev")
public class DevFundingController {

    private static final Logger log = LoggerFactory.getLogger(DevFundingController.class);

    /**
     * Per call. Enough to trade any market here for a long time, and small enough that a typo
     * shows up as a refusal rather than as a chart with 10^18 on the axis.
     */
    static final long MAX_PER_CALL = Money.toWire("10000000");

    private final BalanceService balances;
    private final AssetService assets;
    private final UserRepository users;

    public DevFundingController(BalanceService balances, AssetService assets, UserRepository users) {
        this.balances = balances;
        this.assets = assets;
        this.users = users;
    }

    /**
     * @throws ApiException {@link CommonErrorCode#VALIDATION_FAILED} no such account, or the
     *         amount is not a decimal within {@link #MAX_PER_CALL}. A dev endpoint that will be
     *         deleted does not get error codes of its own: those are a promise and are never
     *         renumbered (docs/reference/error-codes.md).
     */
    @PostMapping("/fund")
    public BalanceView fund(@Valid @RequestBody FundRequest request) {
        User user = users.findByEmail(request.email())
                .orElseThrow(() -> new ApiException(CommonErrorCode.VALIDATION_FAILED));
        Asset asset = assets.getBySymbol(request.asset());
        long amount = parseAmount(request.amount());

        log.warn("DEV PROFILE -- crediting {} {} to user {} with no deposit behind it. "
                + "This must never run in production.", request.amount(), asset.getSymbol(), user.getId());
        // The reference is the caller's, so a provisioning script that runs twice funds once.
        Balance credited = balances.deposit(user.getId(), asset.getId(), amount, ReferenceType.ADMIN,
                "dev-faucet", "dev-faucet:" + user.getId() + ":" + asset.getSymbol() + ":" + request.reference());
        return BalanceView.of(credited, asset);
    }

    private static long parseAmount(String amount) {
        long scaled;
        try {
            scaled = Money.toWire(amount);
        } catch (ArithmeticException | IllegalArgumentException e) {
            throw new ApiException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (scaled <= 0 || scaled > MAX_PER_CALL) {
            throw new ApiException(CommonErrorCode.VALIDATION_FAILED);
        }
        return scaled;
    }

    /** {@code reference} is the caller's idempotency key: the same one credits once. */
    public record FundRequest(@NotBlank String email, @NotBlank String asset,
                              @NotBlank String amount, @NotBlank String reference) {}
}
