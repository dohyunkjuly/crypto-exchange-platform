package com.exchange.spot.matching.kafka;

import java.net.InetSocketAddress;
import java.net.Socket;
import org.junit.jupiter.api.Assumptions;

/**
 * Skips when the real matching engine is running against the same broker, as it is under
 * {@code docker compose --profile full}. The engine has no database, so it matches any symbol,
 * including a test's made-up one: every command is processed twice and every event count
 * doubles. CI starts infrastructure only.
 *
 * <p>Detected by its health port, not its consumer group: the engine assigns its partitions
 * itself, so the group has no members to find. These tests start their engines without a web
 * server, so anything listening there is the real one. spot-settlement's ConsumerReadiness has
 * the equivalent check; test sources are not shared across modules.
 */
final class EngineTestGuards {

    /** application.yml's server.port, published by docker-compose. */
    private static final int ENGINE_HEALTH_PORT = 8090;

    private EngineTestGuards() {
    }

    static void abortIfEngineAppIsRunning() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", ENGINE_HEALTH_PORT), 300);
        } catch (Exception notListening) {
            return;
        }
        Assumptions.abort("the matching engine is running and would process this test's commands "
                + "too -- run `docker compose stop spot-matching-engine` first");
    }
}
