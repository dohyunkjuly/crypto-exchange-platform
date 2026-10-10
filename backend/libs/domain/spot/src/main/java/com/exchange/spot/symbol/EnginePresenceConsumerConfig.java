package com.exchange.spot.symbol;

import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;

/**
 * The heartbeat listener's own container factory. The default one is shaped by each app's
 * {@code spring.kafka.listener} block -- spot-copy's is a batch listener with manual acks --
 * and {@link EnginePresence} wants one record at a time and no acks to manage, whichever app
 * it runs in.
 */
@Configuration
public class EnginePresenceConsumerConfig {

    static final String FACTORY = "enginePresenceContainerFactory";

    @Bean(FACTORY)
    public ConcurrentKafkaListenerContainerFactory<Object, Object> enginePresenceContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setBatchListener(false);
        factory.getContainerProperties().setAckMode(AckMode.BATCH);
        return factory;
    }
}
