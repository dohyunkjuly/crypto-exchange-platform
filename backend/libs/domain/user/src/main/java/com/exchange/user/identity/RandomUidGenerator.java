package com.exchange.user.identity;

import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.function.LongPredicate;
import java.util.random.RandomGenerator;
import org.hibernate.FlushMode;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.generator.EventTypeSets;

/**
 * Customer ids are random ten-digit numbers, not a counter: a counter tells anyone who signs up
 * twice how many signed up in between. Still a BIGINT, so every table, message and the engine
 * that carries a user id is unchanged, and ten digits stay exact as a JSON number in a browser.
 *
 * <p>An id already taken is drawn again, checked against the table before the insert. The
 * primary key stays the final guard: two signups drawing the same unused number in the same
 * instant -- odds of about one in ten billion per pair -- fail the second insert rather than
 * share an id. The column itself no longer counts (V14__user_id_not_counted.sql), so an insert
 * that forgets the id fails rather than quietly taking a counted one.
 *
 * <p>Generated before the insert, so Hibernate persists rather than merges: an assigned id that
 * merged would overwrite whichever customer already had it.
 */
public class RandomUidGenerator implements BeforeExecutionGenerator {

    static final long LOWEST = 1_000_000_000L;
    static final long HIGHEST = 9_999_999_999L;
    static final int ATTEMPTS = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public Object generate(SharedSessionContractImplementor session, Object owner, Object currentValue,
                           EventType eventType) {
        return pick(id -> taken(session, id), RANDOM);
    }

    @Override
    public EnumSet<EventType> getEventTypes() {
        return EventTypeSets.INSERT_ONLY;
    }

    /** A ten-digit number {@code taken} says is free; gives up after {@link #ATTEMPTS} draws. */
    static long pick(LongPredicate taken, RandomGenerator random) {
        for (int i = 0; i < ATTEMPTS; i++) {
            long candidate = random.nextLong(LOWEST, HIGHEST + 1);
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no free customer id after " + ATTEMPTS + " draws");
    }

    /** Manual flush: the lookup must not flush the half-built entity it is choosing an id for. */
    private static boolean taken(SharedSessionContractImplementor session, long id) {
        return !session.createNativeQuery("SELECT 1 FROM user_users WHERE id = :id")
                .setParameter("id", id)
                .setHibernateFlushMode(FlushMode.MANUAL)
                .getResultList()
                .isEmpty();
    }
}
