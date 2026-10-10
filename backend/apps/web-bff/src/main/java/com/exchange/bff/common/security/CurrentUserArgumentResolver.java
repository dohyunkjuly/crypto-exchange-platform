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

/** Resolves a {@code @CurrentUser User} parameter from the request attribute {@link
 *  AuthenticationInterceptor} already populated -- the actual enforcement (and the one
 *  session lookup) happened there, before this ever runs. Registered in
 *  {@code common.config.WebMvcConfig}. */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && User.class.isAssignableFrom(parameter.getParameterType());
    }

    /** @throws ApiException {@link BffHttpErrorCode#NOT_AUTHENTICATED} the interceptor didn't
     *          run for this route -- e.g. it was left off {@code WebMvcConfig}'s
     *          {@code addPathPatterns} by mistake. A controller reaching this with no
     *          attribute set is a configuration bug, not a normal 401 case. */
    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = (HttpServletRequest) webRequest.getNativeRequest();
        Object user = request.getAttribute(AuthenticationInterceptor.CURRENT_USER_ATTRIBUTE);
        if (user == null) {
            throw new ApiException(BffHttpErrorCode.NOT_AUTHENTICATED);
        }
        return user;
    }
}
