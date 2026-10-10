package com.exchange.spot.matching.config;

import com.exchange.spot.matching.engine.MatchingEngine;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The engine itself is a plain object with no Spring annotations — no {@code @Component},
 * no injection, no proxy. That is what lets it be unit tested in milliseconds, and it
 * keeps the framework out of the matching path entirely.
 *
 * <p>One instance per process. It holds every symbol's book, and the single consumer
 * thread is the only thing that touches it.
 *
 * <p>This package is the only framework-aware code in the module. Everything else --
 * the engine, the book, the trigger book -- is plain Java that runs without a container,
 * which is what lets the whole matching path be tested in milliseconds.
 */
@Configuration
public class EngineConfig {

    @Bean
    public MatchingEngine matchingEngine() {
        return new MatchingEngine();
    }
}
