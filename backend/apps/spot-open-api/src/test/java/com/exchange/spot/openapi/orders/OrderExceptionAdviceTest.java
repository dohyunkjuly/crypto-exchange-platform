package com.exchange.spot.openapi.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;

class OrderExceptionAdviceTest {

    @Test
    @DisplayName("every exception the handler is registered for has a code, so none can fall through to a 500")
    @SuppressWarnings("unchecked")
    void everyHandledExceptionHasACode() throws Exception {
        Method handle = OrderExceptionAdvice.class.getMethod("handle", RuntimeException.class);
        Class<?>[] handled = handle.getAnnotation(ExceptionHandler.class).value();
        Map<Class<?>, ?> codes = (Map<Class<?>, ?>) ReflectionTestUtils.getField(OrderExceptionAdvice.class, "CODES");
        assertThat(handled).isNotEmpty();
        for (Class<?> type : handled) {
            assertThat(codes).as(type.getSimpleName()).containsKey(type);
        }
        assertThat(codes.keySet()).containsExactlyInAnyOrder(handled);
    }
}
