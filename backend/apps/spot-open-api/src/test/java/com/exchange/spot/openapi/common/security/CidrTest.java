package com.exchange.spot.openapi.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CidrTest {

    @Test
    void blocksAndBareAddresses() {
        assertThat(Cidr.contains("10.0.0.0/8", "10.200.3.4")).isTrue();
        assertThat(Cidr.contains("10.0.0.0/8", "11.0.0.1")).isFalse();
        assertThat(Cidr.contains("192.168.1.0/28", "192.168.1.15")).isTrue();
        assertThat(Cidr.contains("192.168.1.0/28", "192.168.1.16")).isFalse();
        assertThat(Cidr.contains("203.0.113.7", "203.0.113.7")).isTrue();
        assertThat(Cidr.contains("203.0.113.7", "203.0.113.8")).isFalse();
        assertThat(Cidr.contains("2001:db8::/32", "2001:db8:1::5")).isTrue();
        assertThat(Cidr.contains("2001:db8::/32", "2001:db9::1")).isFalse();
    }

    @Test
    @DisplayName("a malformed allowlist entry admits nobody, and a family mismatch never matches")
    void failsClosed() {
        assertThat(Cidr.contains("not-an-address", "10.0.0.1")).isFalse();
        assertThat(Cidr.contains("10.0.0.0/99", "10.0.0.1")).isFalse();
        assertThat(Cidr.contains("10.0.0.0/8", "::1")).isFalse();
    }
}
