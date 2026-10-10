package com.exchange.bff.common.security;

import com.exchange.user.eligibility.EligibilityService;
import com.exchange.user.identity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Refuses a trading request from an account that is not cleared to trade.
 *
 * <p><b>Costs no query.</b> {@link AuthenticationInterceptor} has already resolved and
 * stashed the {@link User}, and the gate's inputs live on that row (user_users'
 * {@code kyc_level}, {@code kyc_verified_until}, {@code mfa_enabled}), so this is a couple
 * of field reads on an object already in hand. That is the whole reason the check moved
 * out of {@code OrderService}, where it would have cost a read per order.
 *
 * <p>Registered after {@link AuthenticationInterceptor} -- registration order is execution
 * order (see {@code WebMvcConfig}) -- so the user is guaranteed present by the time this
 * runs. A missing one means an endpoint was annotated but left off the authentication
 * gate, which is a wiring bug rather than a client error, so it fails loudly.
 *
 * <p>Only {@link RequiresTrading} handlers are touched; everything else passes straight
 * through. See that annotation for why this is not a path pattern.
 */
@Component
public class TradingEligibilityInterceptor implements HandlerInterceptor {

    private final EligibilityService eligibility;

    public TradingEligibilityInterceptor(EligibilityService eligibility) {
        this.eligibility = eligibility;
    }

    /** @throws com.exchange.user.eligibility.exception.KycRequiredException not verified
     *          far enough, or verification lapsed
     *  @throws com.exchange.user.eligibility.exception.MfaRequiredException no second
     *          factor */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        if (!(handler instanceof HandlerMethod method)
                || method.getMethodAnnotation(RequiresTrading.class) == null) {
            return true;
        }
        Object user = request.getAttribute(AuthenticationInterceptor.CURRENT_USER_ATTRIBUTE);
        if (!(user instanceof User currentUser)) {
            throw new IllegalStateException("@RequiresTrading on a handler that is not "
                    + "behind AuthenticationInterceptor: " + method);
        }
        eligibility.requireCanTrade(currentUser);
        return true;
    }
}
