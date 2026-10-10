package com.exchange.spot.openapi.copy;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.copy.leadorder.CopyAccountOrderHook;
import com.exchange.spot.order.OrderService;
import com.exchange.spot.order.SubAccountOrderHook;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * This is the app a sub-account's key trades through, so it is where a lead account's orders
 * arrive (docs/reference/copy-trading.md). What the hook does is {@code CopyAccountOrderHookTest}'s
 * and web-bff's {@code CopyOrderPathIT}'s; this holds only that it is here at all, since a missing
 * package in the component scan fails nothing and checks nothing.
 */
@SpringBootTest(properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA="})
class CopyHookWiringIT {

    @Autowired private OrderService orders;
    @Autowired private SubAccountOrderHook hook;

    @BeforeAll
    static void requireInfrastructure() {
        for (int port : new int[] {3308, 27018, 6379, 9092}) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", port), 500);
            } catch (Exception e) {
                Assumptions.abort("infrastructure not reachable on " + port + " -- run `docker compose up -d`");
            }
        }
    }

    @Test
    @DisplayName("the order path holds the copy hook")
    void theHookIsWired() {
        assertThat(hook).isInstanceOf(CopyAccountOrderHook.class);
        assertThat(ReflectionTestUtils.getField(orders, "subAccountHook")).isEqualTo(Optional.of(hook));
    }
}
