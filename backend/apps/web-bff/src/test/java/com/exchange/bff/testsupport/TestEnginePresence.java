package com.exchange.bff.testsupport;

import com.exchange.spot.symbol.EnginePresence;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Every partition has an engine, as far as this app's tests are concerned. CI runs the
 * infrastructure only, so no heartbeat ever arrives and the real {@link EnginePresence} would
 * refuse every order a test places. A test of the gate itself flips {@link #live}.
 */
@Component
@Primary
public class TestEnginePresence extends EnginePresence {

    public static volatile boolean live = true;

    @Override
    public boolean isLive(int partition) {
        return live;
    }
}
