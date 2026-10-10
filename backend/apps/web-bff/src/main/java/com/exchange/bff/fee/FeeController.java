package com.exchange.bff.fee;

import com.exchange.bff.common.security.CurrentUser;
import com.exchange.bff.fee.dto.FeeRatesView;
import com.exchange.fee.FeeProduct;
import com.exchange.fee.FeeQueryService;
import com.exchange.user.identity.User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the signed-in user pays to trade.
 *
 * <p>Read by two screens. The order form shows both rates before anyone commits money --
 * without this it can only report the cost afterwards, from the fill. The profile shows the
 * same numbers plus which tier they came from.
 *
 * <p><b>The same resolution the order path uses</b>, through {@code FeeQueryService} rather
 * than a second copy of "override beats tier". A form that computed rates independently
 * could display one number while the engine charged another, and nothing would fail -- the
 * user would find out from a statement, if ever.
 *
 * <p>Requires a session and nothing more. It is the user's own rate, so there is no
 * eligibility gate: someone whose KYC has lapsed cannot place an order but is still entitled
 * to know what trading would cost them.
 *
 * <p>Not per symbol. A {@code FeeTier} is keyed by {@link FeeProduct}, so one pair of rates
 * covers every spot market and a client fetches this once rather than per market it shows.
 */
@RestController
@RequestMapping("/bff/fees")
public class FeeController {

    private final FeeQueryService fees;

    public FeeController(FeeQueryService fees) {
        this.fees = fees;
    }

    @GetMapping
    public FeeRatesView fees(@CurrentUser User user) {
        return FeeRatesView.of(fees.summaryFor(user.getId(), user.getParentUserId(), FeeProduct.SPOT));
    }
}
