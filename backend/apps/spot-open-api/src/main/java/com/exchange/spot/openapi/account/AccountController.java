package com.exchange.spot.openapi.account;

import com.exchange.fee.FeeProduct;
import com.exchange.fee.FeeQueryService;
import com.exchange.spot.openapi.account.dto.AccountView;
import com.exchange.spot.openapi.account.dto.FeeRatesView;
import com.exchange.spot.openapi.common.ratelimit.Weight;
import com.exchange.spot.openapi.common.security.CurrentUser;
import com.exchange.user.apikey.UserApiKey;
import com.exchange.user.eligibility.EligibilityService;
import com.exchange.user.identity.User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /v1/account}: the account behind the key, its standing, and the key's own scope. */
@RestController
@RequestMapping("/v1/account")
public class AccountController {

    private final EligibilityService eligibility;
    private final FeeQueryService fees;

    public AccountController(EligibilityService eligibility, FeeQueryService fees) {
        this.eligibility = eligibility;
        this.fees = fees;
    }

    @GetMapping
    @Weight(10)
    public AccountView account(@CurrentUser User user, @CurrentUser UserApiKey key) {
        return AccountView.of(user, key, eligibility.canTrade(user),
                FeeRatesView.of(fees.summaryFor(user.getId(), user.getParentUserId(), FeeProduct.SPOT)));
    }
}
