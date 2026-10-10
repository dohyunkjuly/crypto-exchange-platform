package com.exchange.bff.dev;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.kyc.KycLevel;
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
import org.springframework.test.context.ActiveProfiles;

/** The dev accounts exist after boot, log in, and a second boot adds nothing. Not cleaned up: they are the point. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "exchange.rate-limit.max-requests=100000"})
@ActiveProfiles("dev")
class DevSeederIT {

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
    @Autowired
    private UserRepository users;
    @Autowired
    private DevSeeder seeder;

    @Test
    @DisplayName("every persona exists as described, and seeding again is a no-op")
    void personasExistAndSeedingIsIdempotent() {
        long before = users.count();
        seeder.seed();
        assertThat(users.count()).isEqualTo(before);

        User alice = users.findByEmail("alice@example.com").orElseThrow();
        assertThat(alice.isEmailVerified()).isTrue();
        assertThat(alice.getKycLevel()).isEqualTo(KycLevel.INTERMEDIATE);
        assertThat(alice.isMfaEnabled()).isTrue();
        User carol = users.findByEmail("carol@example.com").orElseThrow();
        assertThat(carol.getKycLevel()).isEqualTo(KycLevel.NONE);
        assertThat(carol.isMfaEnabled()).isFalse();
    }

    @Test
    @DisplayName("the printed password logs in; an account with 2FA is challenged, one without is let straight in")
    void thePasswordWorks() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> alice = rest.exchange("http://localhost:" + port + "/bff/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "alice@example.com", "password", DevSeeder.PASSWORD), h), Map.class);
        assertThat(alice.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) alice.getBody().get("data")).get("mfaRequired")).isEqualTo(true);

        ResponseEntity<Map> carol = rest.exchange("http://localhost:" + port + "/bff/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "carol@example.com", "password", DevSeeder.PASSWORD), h), Map.class);
        assertThat(carol.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(carol.getHeaders().get(HttpHeaders.SET_COOKIE)).isNotEmpty();
    }
}
