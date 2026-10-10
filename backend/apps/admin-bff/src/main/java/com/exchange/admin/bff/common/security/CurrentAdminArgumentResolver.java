package com.exchange.admin.bff.common.security;

import com.exchange.admin.bff.auth.Authenticated;
import com.exchange.admin.bff.common.errors.AdminErrorCode;
import com.exchange.core.api.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Hands a controller what the interceptor stashed. Nothing stashed means a route was left
 *  off the interceptor's patterns: a configuration bug, refused rather than resolved to null. */
@Component
public class CurrentAdminArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentAdmin.class)
                && Authenticated.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        HttpServletRequest request = (HttpServletRequest) webRequest.getNativeRequest();
        if (!(request.getAttribute(Authenticated.ATTRIBUTE) instanceof Authenticated who)) {
            throw new ApiException(AdminErrorCode.NOT_AUTHENTICATED);
        }
        return who;
    }
}
