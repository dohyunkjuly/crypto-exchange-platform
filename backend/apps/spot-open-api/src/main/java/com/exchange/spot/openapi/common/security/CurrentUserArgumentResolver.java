package com.exchange.spot.openapi.common.security;

import com.exchange.core.api.exception.ApiException;
import com.exchange.spot.openapi.common.errors.OpenApiErrorCode;
import com.exchange.user.apikey.UserApiKey;
import com.exchange.user.identity.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Hands a controller the {@link User} or {@link UserApiKey} the interceptor authenticated.
 *  Reaching this with nothing stashed means the route was left off the interceptor's patterns --
 *  a configuration bug, refused rather than resolved to null. */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        Class<?> type = parameter.getParameterType();
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && (User.class.isAssignableFrom(type) || UserApiKey.class.isAssignableFrom(type));
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = (HttpServletRequest) webRequest.getNativeRequest();
        if (!(request.getAttribute(SignedRequest.ATTRIBUTE) instanceof SignedRequest signed)) {
            throw new ApiException(OpenApiErrorCode.UNKNOWN_API_KEY);
        }
        if (!User.class.isAssignableFrom(parameter.getParameterType())) {
            return signed.key();
        }
        // The interceptor loads the user for exactly the parameters this resolves, so none here
        // means the two have drifted apart: refused, never a null handed to a controller.
        if (signed.user() == null) {
            throw new IllegalStateException("@CurrentUser User on a request whose user was not loaded");
        }
        return signed.user();
    }
}
