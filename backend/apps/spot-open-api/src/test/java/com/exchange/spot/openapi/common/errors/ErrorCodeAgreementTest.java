package com.exchange.spot.openapi.common.errors;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A code is a meaning, not a transport: a number present in both enums must say the same thing. */
class ErrorCodeAgreementTest {

    @Test
    @DisplayName("every number shared by the HTTP and socket enums carries the same message")
    void sharedNumbersAgree() {
        Map<Integer, String> http = new HashMap<>();
        for (OpenApiErrorCode c : OpenApiErrorCode.values()) {
            assertThat(http.put(c.getCode(), c.getMessage())).as("duplicate HTTP code " + c.getCode()).isNull();
        }
        Map<Integer, String> ws = new HashMap<>();
        for (OpenApiWsErrorCode c : OpenApiWsErrorCode.values()) {
            assertThat(ws.put(c.getCode(), c.getMessage())).as("duplicate socket code " + c.getCode()).isNull();
        }
        ws.forEach((code, message) -> {
            if (http.containsKey(code)) {
                assertThat(message).as("code " + code).isEqualTo(http.get(code));
            }
        });
        // The socket's own range never collides with an HTTP meaning.
        ws.keySet().stream().filter(code -> code >= 7000 && code < 8000)
                .forEach(code -> assertThat(http).doesNotContainKey(code));
    }
}
