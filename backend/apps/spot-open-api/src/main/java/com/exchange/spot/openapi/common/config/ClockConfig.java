package com.exchange.spot.openapi.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One clock, injected, so a test can move time past the receive window. Its own class: the
 *  MVC config depends on beans that depend on this, and a bean method there would be a cycle. */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
