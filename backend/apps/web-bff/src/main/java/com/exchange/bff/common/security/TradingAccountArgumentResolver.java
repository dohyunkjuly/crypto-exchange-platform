package com.exchange.bff.common.security;

import com.exchange.bff.common.errors.BffHttpErrorCode;
import com.exchange.core.api.exception.ApiException;
import com.exchange.user.identity.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Resolves a {@code @TradingAccount User}: the session's customer, as {@link
 *  CurrentUserArgumentResolver} does, or the account the request names ({@link TradingAccounts}). */
@Component
public class TradingAccountArgumentResolver implements HandlerMethodArgumentResolver {

    private final TradingAccounts accounts;

    public TradingAccountArgumentResolver(TradingAccounts accounts) {
        this.accounts = accounts;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(TradingAccount.class)
                && User.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = (HttpServletRequest) webRequest.getNativeRequest();
        if (!(request.getAttribute(AuthenticationInterceptor.CURRENT_USER_ATTRIBUTE) instanceof User signedIn)) {
            throw new ApiException(BffHttpErrorCode.NOT_AUTHENTICATED);
        }
        return accounts.resolve(signedIn, request.getHeader(TradingAccounts.HEADER));
    }
}
