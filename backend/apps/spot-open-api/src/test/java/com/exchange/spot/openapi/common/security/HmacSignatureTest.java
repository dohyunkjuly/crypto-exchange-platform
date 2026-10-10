package com.exchange.spot.openapi.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HmacSignatureTest {

    private static final String SECRET = "s3cr3t";
    private static final byte[] BODY = "{\"symbol\":\"BTCUSDT\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("the canonical string is timestamp, method, path with query, body -- in that order, verbatim")
    void canonicalString() {
        assertThat(HmacSignature.canonical("1757800000000", "post", "/v1/orders?x=1", BODY))
                .isEqualTo("1757800000000POST/v1/orders?x=1{\"symbol\":\"BTCUSDT\"}");
    }

    @Test
    @DisplayName("a known vector: HMAC-SHA256 of the canonical string, lowercase hex")
    void knownVector() {
        // echo -n "1GET/v1/time" | openssl dgst -sha256 -hmac "s3cr3t"
        assertThat(HmacSignature.sign(SECRET, HmacSignature.canonical("1", "GET", "/v1/time", new byte[0])))
                .isEqualTo("98160e1df1a086905504e104785be2ce5963f7d955ce2a297d0a49cc51af067d");
    }

    @Test
    @DisplayName("a signature covers the body: the same request with an edited body does not verify")
    void bodyIsCovered() {
        String canonical = HmacSignature.canonical("1757800000000", "POST", "/v1/orders", BODY);
        String signature = HmacSignature.sign(SECRET, canonical);
        assertThat(HmacSignature.verify(SECRET, canonical, signature)).isTrue();
        assertThat(HmacSignature.verify(SECRET, canonical.replace("BTCUSDT", "ETHUSDT"), signature)).isFalse();
        assertThat(HmacSignature.verify("other", canonical, signature)).isFalse();
    }

    @Test
    void hexCaseDoesNotMatterButAbsenceDoes() {
        String canonical = HmacSignature.canonical("1", "GET", "/v1/account", new byte[0]);
        assertThat(HmacSignature.verify(SECRET, canonical, HmacSignature.sign(SECRET, canonical).toUpperCase())).isTrue();
        assertThat(HmacSignature.verify(SECRET, canonical, null)).isFalse();
        assertThat(HmacSignature.verify(SECRET, canonical, "")).isFalse();
    }
}
