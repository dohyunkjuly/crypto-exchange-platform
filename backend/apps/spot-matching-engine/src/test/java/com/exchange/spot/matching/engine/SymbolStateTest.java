package com.exchange.spot.matching.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SymbolState is package-private, so this test lives in the same package. That is also the
 * reason it stays a separate class rather than being nested inside MatchingEngine: a
 * private nested class could only ever be tested through the engine, and {@code tradeId}
 * deserves testing directly — it is the piece whose failure mode is silent.
 */
class SymbolStateTest {

    private static final long T = 1_700_000_000_000_000L;   // microseconds

    @Nested
    @DisplayName("sequence")
    class Sequence {

        @Test
        void starts_at_one_so_zero_can_mean_nothing_yet() {
            SymbolState state = new SymbolState("BTCUSDT", 0);

            assertEquals(0, state.currentSequence());
            assertEquals(1, state.nextSequence());
            assertEquals(2, state.nextSequence());
        }

        @Test
        void restoring_continues_rather_than_restarting() {
            SymbolState state = new SymbolState("BTCUSDT", 0);
            state.restoreCounters(4_711, 6_325_000_000_000L, List.of());

            assertEquals(4_712, state.nextSequence());
        }
    }

    @Nested
    @DisplayName("command ids")
    class CommandIds {

        private final SymbolState state = new SymbolState("BTCUSDT", 0);

        @Test
        void the_first_sight_of_an_id_is_new_and_the_second_is_a_repeat() {
            ByteString id = MatchingEngine.toBytes(UUID.randomUUID());

            assertTrue(state.firstSightOf(id));
            assertFalse(state.firstSightOf(id));
            assertTrue(state.firstSightOf(MatchingEngine.toBytes(UUID.randomUUID())));
        }

        @Test
        @DisplayName("an id-less command is never a repeat: one producer bug must not swallow a market")
        void an_empty_id_is_always_new() {
            assertTrue(state.firstSightOf(ByteString.EMPTY));
            assertTrue(state.firstSightOf(ByteString.EMPTY));
        }

        @Test
        @DisplayName("the memory is bounded, and it is the oldest id that is forgotten")
        void the_oldest_id_is_forgotten_past_the_bound() {
            ByteString oldest = MatchingEngine.toBytes(UUID.randomUUID());
            ByteString second = MatchingEngine.toBytes(UUID.randomUUID());
            state.firstSightOf(oldest);
            state.firstSightOf(second);
            for (int i = 2; i < SymbolState.MAX_REMEMBERED_COMMANDS; i++) {
                state.firstSightOf(MatchingEngine.toBytes(UUID.randomUUID()));
            }
            assertFalse(state.firstSightOf(oldest), "still remembered at exactly the bound");

            state.firstSightOf(MatchingEngine.toBytes(UUID.randomUUID()));   // one past it

            // second first: asking about oldest re-admits it, which would evict second.
            assertFalse(state.firstSightOf(second), "only the oldest is evicted");
            assertTrue(state.firstSightOf(oldest), "and the oldest was");
        }

        @Test
        @DisplayName("a restore carries the ids back in the same order, so eviction continues where it left off")
        void restore_preserves_the_order() {
            ByteString a = MatchingEngine.toBytes(UUID.randomUUID());
            ByteString b = MatchingEngine.toBytes(UUID.randomUUID());
            state.firstSightOf(a);
            state.firstSightOf(b);

            SymbolState restored = new SymbolState("BTCUSDT", 0);
            restored.restoreCounters(0, 0, state.recentCommandIds());

            assertEquals(List.of(a, b), restored.recentCommandIds());
            assertFalse(restored.firstSightOf(a));
            assertFalse(restored.firstSightOf(b));
        }
    }

    @Nested
    @DisplayName("last trade price")
    class LastTradePrice {

        @Test
        @DisplayName("a market that has not traded has no price to trigger against")
        void unset_until_a_fill() {
            SymbolState state = new SymbolState("BTCUSDT", 0);

            assertFalse(state.hasTraded());
            assertEquals(0, state.lastTradePrice());

            state.recordTrade(6_325_000_000_000L);
            assertTrue(state.hasTraded());
            assertEquals(6_325_000_000_000L, state.lastTradePrice());
        }
    }

    @Nested
    @DisplayName("trade ids")
    class TradeIds {

        @Test
        @DisplayName("identical inputs give an identical id — this is what replay depends on")
        void deterministic_across_instances() {
            SymbolState first = new SymbolState("BTCUSDT", 3);
            SymbolState second = new SymbolState("BTCUSDT", 3);
            first.nextSequence();
            second.nextSequence();

            // UUID.randomUUID() here would pass every other test in this class and
            // silently make recovery unreconcilable.
            assertEquals(first.tradeId(T), second.tradeId(T));
        }

        @Test
        void a_later_sequence_gives_a_different_id() {
            SymbolState state = new SymbolState("BTCUSDT", 0);
            state.nextSequence();
            UUID first = state.tradeId(T);
            state.nextSequence();

            assertNotEquals(first, state.tradeId(T));
        }

        @Test
        @DisplayName("two markets at the same sequence and instant do not collide")
        void unique_across_symbols() {
            Set<UUID> ids = new HashSet<>();
            String[] symbols = {
                    "BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT", "DOGEUSDT",
                    "ADAUSDT", "AVAXUSDT", "LINKUSDT", "BTCUSDC", "ETHBTC"};
            for (String symbol : symbols) {
                SymbolState state = new SymbolState(symbol, 0);
                state.nextSequence();
                ids.add(state.tradeId(T));
            }
            assertEquals(symbols.length, ids.size(), "a hash collision would reuse a trade id");
        }

        @Test
        @DisplayName("ids sort by time, so the BINARY(16) primary key does not fragment")
        void time_ordered() {
            SymbolState state = new SymbolState("BTCUSDT", 0);
            state.nextSequence();

            long earlier = state.tradeId(T).getMostSignificantBits();
            long later = state.tradeId(T + 5_000_000).getMostSignificantBits();

            assertTrue(later > earlier,
                    "a random id would insert anywhere and split B-tree pages");
        }

        @Test
        void ids_stay_unique_across_a_long_run_of_sequences() {
            SymbolState state = new SymbolState("BTCUSDT", 0);
            Set<UUID> ids = new HashSet<>();
            for (int i = 0; i < 10_000; i++) {
                state.nextSequence();
                ids.add(state.tradeId(T));
            }
            assertEquals(10_000, ids.size());
        }
    }

    @Nested
    @DisplayName("partition")
    class Partition {

        @Test
        @DisplayName("a symbol remembers its partition, so a snapshot describes only its own")
        void records_the_partition_it_arrived_on() {
            assertEquals(5, new SymbolState("BTCUSDT", 5).partition());
        }
    }
}
