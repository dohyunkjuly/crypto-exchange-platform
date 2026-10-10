package com.exchange.user.apikey;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import com.exchange.common.id.Ulid;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.EnumSet;
import org.hibernate.annotations.IdGeneratorType;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.generator.EventTypeSets;

/**
 * A ULID drawn when the row is first inserted, as orders, trades and ledger lines are keyed:
 * time-ordered, never counted, and never colliding in practice, so no lookup or retry. Generated
 * before the insert, so Hibernate persists rather than merges.
 */
@IdGeneratorType(UlidId.Generator.class)
@Retention(RUNTIME)
@Target(FIELD)
public @interface UlidId {

    class Generator implements BeforeExecutionGenerator {

        @Override
        public Object generate(SharedSessionContractImplementor session, Object owner, Object currentValue,
                               EventType eventType) {
            return Ulid.randomUuid();
        }

        @Override
        public EnumSet<EventType> getEventTypes() {
            return EventTypeSets.INSERT_ONLY;
        }
    }
}
