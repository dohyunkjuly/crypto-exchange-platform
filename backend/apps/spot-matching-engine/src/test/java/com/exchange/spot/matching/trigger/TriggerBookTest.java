package com.exchange.spot.matching.trigger;

import static com.exchange.contracts.spot.v1.OrderType.ORDER_TYPE_STOP_LOSS;
import static com.exchange.contracts.spot.v1.OrderType.ORDER_TYPE_STOP_LOSS_LIMIT;
import static com.exchange.contracts.spot.v1.OrderType.ORDER_TYPE_TAKE_PROFIT;
import static com.exchange.contracts.spot.v1.Side.SIDE_BUY;
import static com.exchange.contracts.spot.v1.Side.SIDE_SELL;
import static com.exchange.contracts.spot.v1.TimeInForce.TIME_IN_FORCE_GTC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.exchange.contracts.spot.v1.AmendRejectReason;
import com.exchange.contracts.spot.v1.OrderType;
import com.exchange.contracts.spot.v1.SelfTradePreventionMode;
import com.exchange.contracts.spot.v1.Side;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TriggerBookTest {

    private static final long ONE = 100_000_000L;
    private static final long P_62000 = 6_200_000_000_000L;
    private static final long P_63000 = 6_300_000_000_000L;
    private static final long P_64000 = 6_400_000_000_000L;

    private final TriggerBook triggers = new TriggerBook();

    private static PendingOrder pending(long userId, Side side, OrderType type, long stop) {
        return new PendingOrder(UUID.randomUUID(), userId, side, type, TIME_IN_FORCE_GTC,
                stop, 0, ONE, -2, 7, null,
                SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_UNSPECIFIED, 0, false);
    }

    private static PendingOrder trailing(long userId, Side side, OrderType type,
                                         long activationStop, int trailingDeltaBps) {
        return new PendingOrder(UUID.randomUUID(), userId, side, type, TIME_IN_FORCE_GTC,
                activationStop, 0, ONE, -2, 7, null,
                SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_UNSPECIFIED,
                trailingDeltaBps, false);
    }

    @Nested
    @DisplayName("direction")
    class Direction {

        @Test
        @DisplayName("a stop-loss sell fires on a fall; a stop-loss buy on a rise")
        void stop_loss_depends_on_side() {
            assertEquals(TriggerDirection.FALL,
                    TriggerDirection.of(ORDER_TYPE_STOP_LOSS, SIDE_SELL));
            assertEquals(TriggerDirection.RISE,
                    TriggerDirection.of(ORDER_TYPE_STOP_LOSS, SIDE_BUY));
        }

        @Test
        @DisplayName("take-profit is the mirror image of stop-loss")
        void take_profit_is_inverted() {
            assertEquals(TriggerDirection.RISE,
                    TriggerDirection.of(ORDER_TYPE_TAKE_PROFIT, SIDE_SELL));
            assertEquals(TriggerDirection.FALL,
                    TriggerDirection.of(ORDER_TYPE_TAKE_PROFIT, SIDE_BUY));
        }
    }

    @Nested
    @DisplayName("immediate trigger")
    class Immediate {

        @Test
        void a_stop_already_below_the_market_would_fire_at_once() {
            PendingOrder order = pending(1, SIDE_SELL, ORDER_TYPE_STOP_LOSS, P_64000);
            // Market is at 63000, already below the 64000 stop — it would fire instantly.
            assertTrue(TriggerBook.wouldTriggerImmediately(order, P_63000));
        }

        @Test
        void a_stop_below_the_market_waits() {
            PendingOrder order = pending(1, SIDE_SELL, ORDER_TYPE_STOP_LOSS, P_62000);
            assertFalse(TriggerBook.wouldTriggerImmediately(order, P_63000));
        }

        @Test
        @DisplayName("a market that has never traded rejects rather than parking")
        void no_price_means_reject() {
            PendingOrder order = pending(1, SIDE_SELL, ORDER_TYPE_STOP_LOSS, P_62000);
            assertTrue(TriggerBook.wouldTriggerImmediately(order, 0));
        }
    }

    @Nested
    @DisplayName("firing")
    class Firing {

        @Test
        void a_fall_fires_only_what_the_price_reached() {
            triggers.park(pending(1, SIDE_SELL, ORDER_TYPE_STOP_LOSS, P_62000));
            PendingOrder nearer = pending(2, SIDE_SELL, ORDER_TYPE_STOP_LOSS, P_63000);
            triggers.park(nearer);

            List<PendingOrder> fired = triggers.onPrice(P_63000);

            assertEquals(1, fired.size());
            assertEquals(nearer.id(), fired.get(0).id());
            assertEquals(1, triggers.pendingCount());     // the 62000 stop still waits
        }

        @Test
        @DisplayName("a gap down fires everything it passed, nearest the market first")
        void a_large_move_fires_several_in_order() {
            triggers.park(pending(1, SIDE_SELL, ORDER_TYPE_STOP_LOSS, P_62000));
            triggers.park(pending(2, SIDE_SELL, ORDER_TYPE_STOP_LOSS, P_63000));
            triggers.park(pending(3, SIDE_SELL, ORDER_TYPE_STOP_LOSS, P_64000));

            List<PendingOrder> fired = triggers.onPrice(P_62000);

            assertEquals(3, fired.size());
            assertEquals(P_64000, fired.get(0).stopPrice());   // highest reached first
            assertEquals(P_63000, fired.get(1).stopPrice());
            assertEquals(P_62000, fired.get(2).stopPrice());
            assertTrue(triggers.isEmpty());
        }

        @Test
        void a_rise_fires_the_lowest_stops_first() {
            triggers.park(pending(1, SIDE_BUY, ORDER_TYPE_STOP_LOSS, P_63000));
            triggers.park(pending(2, SIDE_BUY, ORDER_TYPE_STOP_LOSS, P_64000));

            List<PendingOrder> fired = triggers.onPrice(P_64000);

            assertEquals(2, fired.size());
            assertEquals(P_63000, fired.get(0).stopPrice());
            assertEquals(P_64000, fired.get(1).stopPrice());
        }

        @Test
        void a_price_that_reaches_nothing_fires_nothing() {
            triggers.park(pending(1, SIDE_SELL, ORDER_TYPE_STOP_LOSS, P_62000));

            assertTrue(triggers.onPrice(P_63000).isEmpty());
            assertEquals(1, triggers.pendingCount());
        }

        @Test
        void firing_removes_the_order_so_it_cannot_fire_twice() {
            triggers.park(pending(1, SIDE_SELL, ORDER_TYPE_STOP_LOSS, P_63000));

            assertEquals(1, triggers.onPrice(P_62000).size());
            assertTrue(triggers.onPrice(P_62000).isEmpty());
        }
    }

    @Nested
    @DisplayName("conversion")
    class Conversion {

        @Test
        @DisplayName("a plain stop becomes a market order — the point is to get out")
        void stop_loss_becomes_market() {
            var order = pending(1, SIDE_SELL, ORDER_TYPE_STOP_LOSS, P_62000).toActiveOrder();

            assertEquals(OrderType.ORDER_TYPE_MARKET, order.type());
            assertEquals(0, order.price());
        }

        @Test
        void stop_loss_limit_becomes_a_limit_at_its_limit_price() {
            PendingOrder pending = new PendingOrder(UUID.randomUUID(), 1, SIDE_SELL,
                    ORDER_TYPE_STOP_LOSS_LIMIT, TIME_IN_FORCE_GTC,
                    P_62000, P_62000 - ONE, ONE, -2, 7, null,
                    SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_UNSPECIFIED, 0, false);

            var order = pending.toActiveOrder();

            assertEquals(OrderType.ORDER_TYPE_LIMIT, order.type());
            assertEquals(P_62000 - ONE, order.price());
        }

        @Test
        void the_order_id_survives_the_conversion() {
            PendingOrder pending = pending(1, SIDE_SELL, ORDER_TYPE_STOP_LOSS, P_62000);
            assertEquals(pending.id(), pending.toActiveOrder().id());
        }
    }

    @Nested
    @DisplayName("trailing")
    class Trailing {

        @Test
        @DisplayName("a FALL order ratchets its extreme up, never down, and fires on the "
                + "correct reversal")
        void fall_order_ratchets_up_and_fires_on_reversal() {
            PendingOrder order = trailing(1, SIDE_SELL, ORDER_TYPE_STOP_LOSS, 0, 100); // 1%
            triggers.activateTrailing(order, P_62000);

            assertTrue(triggers.onPrice(P_63000).isEmpty());   // ratchets up, no fire
            assertTrue(triggers.onPrice(P_64000).isEmpty());   // ratchets up again

            long dipButNotFar = 6_350_000_000_000L;   // inside the 1% band off the P_64000 peak
            assertTrue(triggers.onPrice(dipButNotFar).isEmpty());
            assertEquals(P_64000, triggers.snapshotTrailing().get(0).extremePrice(),
                    "a favorable dip inside the band must not un-ratchet the extreme");

            List<PendingOrder> fired = triggers.onPrice(P_63000);   // clears the 1% band

            assertEquals(1, fired.size());
            assertEquals(order.id(), fired.get(0).id());
        }

        @Test
        @DisplayName("a RISE order ratchets its extreme down, never up")
        void rise_order_ratchets_down_and_fires_on_reversal() {
            PendingOrder order = trailing(1, SIDE_BUY, ORDER_TYPE_STOP_LOSS, 0, 100); // 1%
            triggers.activateTrailing(order, P_64000);

            assertTrue(triggers.onPrice(P_63000).isEmpty());   // ratchets down
            assertTrue(triggers.onPrice(P_62000).isEmpty());   // ratchets down again

            long riseButNotFar = 6_250_000_000_000L;   // inside the 1% band off the P_62000 low
            assertTrue(triggers.onPrice(riseButNotFar).isEmpty());

            List<PendingOrder> fired = triggers.onPrice(P_63000);   // clears the 1% band

            assertEquals(1, fired.size());
        }

        @Test
        @DisplayName("no activation price starts tracking immediately from the current price")
        void no_activation_price_starts_tracking_immediately() {
            PendingOrder order = trailing(1, SIDE_SELL, ORDER_TYPE_STOP_LOSS, 0, 100);
            triggers.activateTrailing(order, P_62000);

            List<TrailingOrder> active = triggers.snapshotTrailing();
            assertEquals(1, active.size());
            assertEquals(P_62000, active.get(0).extremePrice());
            assertEquals(1, triggers.pendingCount());
        }

        @Test
        @DisplayName("an activation-gated trailing order waits, then starts tracking once "
                + "the activation price is reached")
        void activation_gated_trailing_starts_once_reached() {
            PendingOrder order = trailing(1, SIDE_SELL, ORDER_TYPE_STOP_LOSS, P_63000, 100);
            triggers.park(order);

            assertTrue(triggers.snapshotTrailing().isEmpty());
            assertEquals(1, triggers.pendingCount());

            List<PendingOrder> fired = triggers.onPrice(P_63000);

            assertTrue(fired.isEmpty(), "reaching the activation price starts tracking, "
                    + "it does not fire the order");
            assertEquals(1, triggers.snapshotTrailing().size());
            assertEquals(P_63000, triggers.snapshotTrailing().get(0).extremePrice());
            assertEquals(1, triggers.pendingCount());
        }

        @Test
        @DisplayName("cancel reaches an actively-trailing order, not just a parked one")
        void cancel_reaches_an_active_trailing_order() {
            PendingOrder order = trailing(1, SIDE_SELL, ORDER_TYPE_STOP_LOSS, 0, 100);
            triggers.activateTrailing(order, P_62000);

            assertNotNull(triggers.cancel(order.id()));
            assertTrue(triggers.isEmpty());
        }
    }

    @Nested
    @DisplayName("cancel")
    class Cancel {

        @Test
        void cancelling_removes_a_pending_order() {
            PendingOrder order = pending(1, SIDE_SELL, ORDER_TYPE_STOP_LOSS, P_62000);
            triggers.park(order);

            assertNotNull(triggers.cancel(order.id()));
            assertTrue(triggers.isEmpty());
            assertTrue(triggers.onPrice(P_62000).isEmpty());
        }

        @Test
        void cancelling_something_that_already_fired_is_a_no_op() {
            PendingOrder order = pending(1, SIDE_SELL, ORDER_TYPE_STOP_LOSS, P_63000);
            triggers.park(order);
            triggers.onPrice(P_62000);

            assertNull(triggers.cancel(order.id()));
        }

        @Test
        void cancel_all_takes_only_that_users_orders() {
            triggers.park(pending(1, SIDE_SELL, ORDER_TYPE_STOP_LOSS, P_62000));
            triggers.park(pending(1, SIDE_BUY, ORDER_TYPE_STOP_LOSS, P_64000));
            triggers.park(pending(2, SIDE_SELL, ORDER_TYPE_STOP_LOSS, P_62000));

            assertEquals(2, triggers.cancelAllFor(1).size());
            assertEquals(1, triggers.pendingCount());
        }

        @Test
        void a_filtered_cancel_all_takes_only_what_matches_every_filter() {
            PendingOrder sellStop = pending(1, SIDE_SELL, ORDER_TYPE_STOP_LOSS, P_62000);
            PendingOrder buyStop = pending(1, SIDE_BUY, ORDER_TYPE_STOP_LOSS, P_64000);
            PendingOrder sellStopLimit = pending(1, SIDE_SELL, ORDER_TYPE_STOP_LOSS_LIMIT, P_62000);
            triggers.park(sellStop);
            triggers.park(buyStop);
            triggers.park(sellStopLimit);

            assertEquals(List.of(sellStop),
                    triggers.cancelAllFor(1, SIDE_SELL, ORDER_TYPE_STOP_LOSS));
            assertEquals(2, triggers.pendingCount());
        }

        /** A trailing order that has started tracking has left {@code index} for
         *  {@code activeTrailing}; a cancel-all that only walked the first would miss it. */
        @Test
        void a_filtered_cancel_all_reaches_an_actively_trailing_order() {
            PendingOrder tracking = trailing(1, SIDE_SELL, ORDER_TYPE_STOP_LOSS, P_64000, 100);
            triggers.park(tracking);
            triggers.onPrice(P_64000);   // reaches its activation price and starts tracking

            assertEquals(1, triggers.cancelAllFor(1, SIDE_SELL, ORDER_TYPE_STOP_LOSS).size());
            assertTrue(triggers.isEmpty());
        }

        @Test
        void a_filtered_cancel_all_leaves_an_actively_trailing_order_of_another_type() {
            PendingOrder tracking = trailing(1, SIDE_SELL, ORDER_TYPE_STOP_LOSS, P_64000, 100);
            triggers.park(tracking);
            triggers.onPrice(P_64000);

            assertTrue(triggers.cancelAllFor(1, SIDE_BUY, null).isEmpty());
            assertEquals(1, triggers.pendingCount());
        }
    }

    @Nested
    @DisplayName("amend")
    class Amend {

        @Test
        @DisplayName("a new stop price moves the trigger: the old price no longer fires it")
        void new_stop_price_moves_the_trigger() {
            PendingOrder order = pending(1, SIDE_SELL, ORDER_TYPE_STOP_LOSS, P_63000);
            triggers.park(order);

            TriggerAmendResult result = triggers.amend(order.id(), P_63000 - ONE, null, null, P_64000);

            assertFalse(result.isRejected());
            assertEquals(P_63000, result.before().stopPrice());
            assertTrue(triggers.onPrice(P_63000).isEmpty());
            assertEquals(1, triggers.onPrice(P_63000 - ONE).size());
        }

        @Test
        @DisplayName("a new stop price already met is refused, and the old trigger still stands")
        void stop_price_already_met_is_refused() {
            PendingOrder order = pending(1, SIDE_SELL, ORDER_TYPE_STOP_LOSS, P_63000);
            triggers.park(order);

            TriggerAmendResult result = triggers.amend(order.id(), P_64000 + ONE, null, null, P_64000);

            assertEquals(AmendRejectReason.AMEND_REJECT_REASON_WOULD_TRIGGER_IMMEDIATELY,
                    result.rejection());
            assertEquals(P_63000, triggers.find(order.id()).stopPrice());
        }

        @Test
        @DisplayName("a plain stop has no limit price to amend")
        void limit_price_on_a_market_firing_order_is_refused() {
            PendingOrder order = pending(1, SIDE_SELL, ORDER_TYPE_STOP_LOSS, P_63000);
            triggers.park(order);

            assertEquals(AmendRejectReason.AMEND_REJECT_REASON_FIELD_NOT_AMENDABLE,
                    triggers.amend(order.id(), null, P_63000, null, P_64000).rejection());
        }

        @Test
        @DisplayName("a trailing order takes a new size and nothing else, parked or tracking")
        void trailing_order_is_size_only() {
            PendingOrder parked = trailing(1, SIDE_SELL, ORDER_TYPE_STOP_LOSS, P_63000, 100);
            PendingOrder tracking = trailing(1, SIDE_SELL, ORDER_TYPE_STOP_LOSS, 0, 100);
            triggers.park(parked);
            triggers.activateTrailing(tracking, P_64000);

            for (PendingOrder order : List.of(parked, tracking)) {
                assertEquals(AmendRejectReason.AMEND_REJECT_REASON_FIELD_NOT_AMENDABLE,
                        triggers.amend(order.id(), P_63000 - ONE, null, null, P_64000).rejection());
                TriggerAmendResult sized = triggers.amend(order.id(), null, null, 2 * ONE, P_64000);
                assertFalse(sized.isRejected());
                assertEquals(2 * ONE, sized.after().quantity());
            }
            assertEquals(2 * ONE, triggers.snapshotTrailing().get(0).quantity());
        }

        @Test
        @DisplayName("an order that is not parked is left for the book to look for")
        void unknown_order_is_not_parked() {
            assertFalse(triggers.amend(UUID.randomUUID(), null, null, ONE, P_64000).isParked());
        }
    }
}
