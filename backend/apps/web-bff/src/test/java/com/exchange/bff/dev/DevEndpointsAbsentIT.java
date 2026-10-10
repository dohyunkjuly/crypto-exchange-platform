package com.exchange.bff.dev;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Neither dev endpoint exists without the `dev` profile. This is the one property of
 * {@link DevFundingController} and {@link DevAccountsController} that a production deployment
 * depends on, so it gets a context of its own with no profile active -- asserting it inside a
 * dev-profile test would prove nothing.
 *
 * <p>Both live under {@code /dev}, which {@code AuthenticationInterceptor} does not cover, so
 * these calls reach the router and a missing route answers 404. Under {@code /bff} the filter
 * would answer 401 first, whether the route existed or not -- and a test that accepted that
 * would pass against an endpoint that was very much there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "exchange.rate-limit.max-requests=100000"})
class DevEndpointsAbsentIT {

    @BeforeAll
    static void requireInfrastructure() {
        for (int port : new int[] {3308, 6379, 9092, 27018}) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", port), 500);
            } catch (Exception e) {
                Assumptions.abort("port " + port + " not reachable -- run `docker compose up -d`");
            }
        }
    }

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("POST /dev/fund is not an endpoint when the dev profile is off")
    void theFaucetIsNotThere() {
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response = rest.exchange("http://localhost:" + port + "/dev/fund",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "nobody@example.com", "asset", "USDT",
                        "amount", "1000", "reference", "x"), json),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("POST /dev/accounts is not an endpoint when the dev profile is off")
    void theAccountFactoryIsNotThere() {
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response = rest.exchange("http://localhost:" + port + "/dev/accounts",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("count", 1, "label", "it", "fund", "1000"), json),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
