package com.exchange.core.api.response;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Auto-wraps every successful controller response into {@link HttpApiResponse}, so a
 * controller can keep returning its DTO directly (per SPRING_BOOT_GUIDE.md) while the
 * client still always sees the same status/code/data/message envelope {@code
 * HttpApiExceptionHandler} uses for failures.
 *
 * <p>{@code basePackages = "com.exchange"} scopes this to our own controllers only --
 * Actuator ({@code /actuator/health}) and springdoc ({@code /v3/api-docs}) live outside it,
 * and wrapping their bodies would corrupt shapes other tooling depends on being exact.
 *
 * <p>The {@code instanceof} guard matters for a different reason: this advice is
 * consulted for responses written by ANY matched controller's request, including one an
 * {@code @ExceptionHandler} in another advice produced -- so a response {@code
 * HttpApiExceptionHandler} already built as an {@code HttpApiResponse} must pass through
 * unchanged, not get wrapped a second time.
 */
@RestControllerAdvice(basePackages = "com.exchange")
public class HttpApiResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof HttpApiResponse<?>) {
            return body;
        }
        int status = response instanceof ServletServerHttpResponse servletResponse
                ? servletResponse.getServletResponse().getStatus()
                : 200;
        return HttpApiResponse.success(status, body);
    }
}
