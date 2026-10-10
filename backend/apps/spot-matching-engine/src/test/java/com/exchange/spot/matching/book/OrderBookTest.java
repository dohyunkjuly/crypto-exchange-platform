package com.exchange.spot.matching.book;

import static com.exchange.contracts.spot.v1.OrderType.ORDER_TYPE_LIMIT;
import static com.exchange.contracts.spot.v1.OrderType.ORDER_TYPE_LIMIT_MAKER;
import static com.exchange.contracts.spot.v1.OrderType.ORDER_TYPE_MARKET;
import static com.exchange.contracts.spot.v1.Side.SIDE_BUY;
import static com.exchange.contracts.spot.v1.Side.SIDE_SELL;
import static com.exchange.contracts.spot.v1.TimeInForce.TIME_IN_FORCE_FOK;
import static com.exchange.contracts.spot.v1.TimeInForce.TIME_IN_FORCE_GTC;
import static com.exchange.contracts.spot.v1.TimeInForce.TIME_IN_FORCE_IOC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.exchange.common.money.Money;
import com.exchange.contracts.spot.v1.AmendRejectReason;
import com.exchange.contracts.spot.v1.OrderType;
import com.exchange.contracts.spot.v1.RejectReason;
import com.exchange.contracts.spot.v1.SelfTradePreventionMode;
import com.exchange.contracts.spot.v1.Side;
import com.exchange.contracts.spot.v1.TimeInForce;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OrderBookTest {

    private static final long ONE = 100_000_000L;      // 1.0 scaled by 1e8
    private static final long P_63250 = 6_325_000_000_000L;
    private static final long P_63300 = 6_330_000_000_000L;
    private static final long P_63200 = 6_320_000_000_000L;

    private final OrderBook book = new OrderBook("BTCUSDT");

    private static Order limit(long userId, Side side, long price, long qty) {
        return new Order(UUID.randomUUID(), userId, side, ORDER_TYPE_LIMIT,
                TIME_IN_FORCE_GTC, price, qty);
    }

    private static Order limit(long userId, Side side, long price, long qty,
                               SelfTradePreventionMode stp) {
        return new Order(UUID.randomUUID(), userId, side, ORDER_TYPE_LIMIT,
                TIME_IN_FORCE_GTC, price, qty, 0, 0, stp);
    }

    private static Order order(long userId, Side side, OrderType type,
                               TimeInForce tif, long price, long qty) {
        return new Order(UUID.randomUUID(), userId, side, type, tif, price, qty);
    }

    private static Order order(long userId, Side side, OrderType type, TimeInForce tif,
                               long price, long qty, SelfTradePreventionMode stp) {
        return new Order(UUID.randomUUID(), userId, side, type, tif, price, qty, 0, 0, stp);
    }

    /** A market order sized by quote-asset budget rather than base quantity. */
    private static Order marketQuote(long userId, Side side, long quoteBudget) {
        return marketQuote(userId, side, quoteBudget,
                SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_UNSPECIFIED);
    }

    private static Order marketQuote(long userId, Side side, long quoteBudget,
                                     SelfTradePreventionMode stp) {
        return new Order(UUID.randomUUID(), userId, side, ORDER_TYPE_MARKET, TIME_IN_FORCE_IOC,
                0, quoteBudget, 0, 0, stp, true);
    }

    @Nested
    @DisplayName("resting")
    class Resting {

        @Test
        void an_order_that_does_not_cross_rests() {
            MatchResult result = book.submit(limit(1, SIDE_BUY, P_63250, ONE));

            assertTrue(result.fills().isEmpty());
            assertEquals(ONE, result.restingQuantity());
            assertEquals(P_63250, book.bestBid());
            assertEquals(1, book.restingOrderCount());
        }

        @Test
        void best_bid_is_the_highest_and_best_ask_the_lowest() {
            book.submit(limit(1, SIDE_BUY, P_63200, ONE));
            book.submit(limit(2, SIDE_BUY, P_63250, ONE));
            book.submit(limit(3, SIDE_SELL, P_63300, ONE));
            book.submit(limit(4, SIDE_SELL, P_63300 + ONE, ONE));

            assertEquals(P_63250, book.bestBid());
            assertEquals(P_63300, book.bestAsk());
        }

        @Test
        void depth_aggregates_every_order_at_a_price() {
            book.submit(limit(1, SIDE_BUY, P_63250, ONE));
            book.submit(limit(2, SIDE_BUY, P_63250, 2 * ONE));
            book.submit(limit(3, SIDE_BUY, P_63200, ONE));

            List<long[]> depth = book.bidDepth(10);
            assertEquals(2, depth.size());
            assertEquals(P_63250, depth.get(0)[0]);
            assertEquals(3 * ONE, depth.get(0)[1]);   // two orders, one level
            assertEquals(P_63200, depth.get(1)[0]);
        }
    }

    @Nested
    @DisplayName("matching")
    class Matching {

        @Test
        @DisplayName("the taker pays the MAKER's price, not their own")
        void price_improvement_goes_to_the_taker() {
            book.submit(limit(1, SIDE_SELL, P_63250, ONE));

            // Willing to pay 63300, but the resting seller was there at 63250.
            MatchResult result = book.submit(limit(2, SIDE_BUY, P_63300, ONE));

            assertEquals(1, result.fills().size());
            assertEquals(P_63250, result.fills().get(0).price());
        }

        @Test
        @DisplayName("time priority: the earlier order at a price fills first")
        void time_priority_within_a_level() {
            Order first = limit(1, SIDE_SELL, P_63250, ONE);
            Order second = limit(2, SIDE_SELL, P_63250, ONE);
            book.submit(first);
            book.submit(second);

            MatchResult result = book.submit(limit(3, SIDE_BUY, P_63250, ONE));

            assertEquals(1, result.fills().size());
            assertEquals(first.id(), result.fills().get(0).makerOrderId());
        }

        @Test
        @DisplayName("price priority beats time priority across levels")
        void price_priority_across_levels() {
            Order expensive = limit(1, SIDE_SELL, P_63300, ONE);   // rested first
            Order cheap = limit(2, SIDE_SELL, P_63250, ONE);       // but cheaper
            book.submit(expensive);
            book.submit(cheap);

            MatchResult result = book.submit(limit(3, SIDE_BUY, P_63300, ONE));

            assertEquals(cheap.id(), result.fills().get(0).makerOrderId());
        }

        @Test
        void a_large_taker_sweeps_several_levels() {
            book.submit(limit(1, SIDE_SELL, P_63250, ONE));
            book.submit(limit(2, SIDE_SELL, P_63300, ONE));
            book.submit(limit(3, SIDE_SELL, P_63300 + ONE, ONE));

            MatchResult result = book.submit(limit(4, SIDE_BUY, P_63300, 3 * ONE));

            assertEquals(2, result.fills().size());          // only two levels crossed
            assertEquals(2 * ONE, result.filledQuantity());
            assertEquals(ONE, result.restingQuantity());     // remainder rests as a bid
            assertEquals(P_63300, book.bestBid());
        }

        @Test
        void a_partial_fill_leaves_the_maker_on_the_book() {
            book.submit(limit(1, SIDE_SELL, P_63250, 3 * ONE));

            MatchResult result = book.submit(limit(2, SIDE_BUY, P_63250, ONE));

            assertEquals(2 * ONE, result.fills().get(0).makerRemaining());
            assertEquals(2 * ONE, book.askDepth(1).get(0)[1]);
            assertEquals(1, book.restingOrderCount());
        }

        @Test
        void a_fill_never_exceeds_the_ordered_quantity() {
            Order maker = limit(1, SIDE_SELL, P_63250, ONE);
            book.submit(maker);
            book.submit(limit(2, SIDE_BUY, P_63250, ONE));

            assertThrows(IllegalStateException.class, () -> maker.reduce(ONE));
        }
    }

    @Nested
    @DisplayName("time in force")
    class TimeInForceRules {

        @Test
        void ioc_fills_what_it_can_and_cancels_the_rest() {
            book.submit(limit(1, SIDE_SELL, P_63250, ONE));

            MatchResult result = book.submit(
                    order(2, SIDE_BUY, ORDER_TYPE_LIMIT, TIME_IN_FORCE_IOC, P_63250, 3 * ONE));

            assertEquals(ONE, result.filledQuantity());
            assertEquals(2 * ONE, result.canceledQuantity());
            assertEquals(0, result.restingQuantity());
            assertNull(book.bestBid());                  // nothing rested
        }

        @Test
        void fok_rejects_when_liquidity_is_short_and_leaves_the_book_untouched() {
            book.submit(limit(1, SIDE_SELL, P_63250, ONE));

            MatchResult result = book.submit(
                    order(2, SIDE_BUY, ORDER_TYPE_LIMIT, TIME_IN_FORCE_FOK, P_63250, 3 * ONE));

            assertTrue(result.isRejected());
            assertEquals(RejectReason.REJECT_REASON_INSUFFICIENT_LIQUIDITY, result.rejection());
            assertTrue(result.fills().isEmpty());
            assertEquals(ONE, book.askDepth(1).get(0)[1]);   // untouched
        }

        @Test
        void fok_fills_entirely_when_liquidity_is_there() {
            book.submit(limit(1, SIDE_SELL, P_63250, 2 * ONE));
            book.submit(limit(2, SIDE_SELL, P_63300, 2 * ONE));

            MatchResult result = book.submit(
                    order(3, SIDE_BUY, ORDER_TYPE_LIMIT, TIME_IN_FORCE_FOK, P_63300, 3 * ONE));

            assertTrue(!result.isRejected());
            assertEquals(3 * ONE, result.filledQuantity());
        }

        @Test
        void a_market_order_never_rests() {
            book.submit(limit(1, SIDE_SELL, P_63250, ONE));

            MatchResult result = book.submit(
                    order(2, SIDE_BUY, ORDER_TYPE_MARKET, TIME_IN_FORCE_IOC, 0, 5 * ONE));

            assertEquals(ONE, result.filledQuantity());
            assertEquals(4 * ONE, result.canceledQuantity());
            assertNull(book.bestBid());
        }

        @Test
        void a_market_order_against_an_empty_book_fills_nothing() {
            MatchResult result = book.submit(
                    order(1, SIDE_BUY, ORDER_TYPE_MARKET, TIME_IN_FORCE_IOC, 0, ONE));

            assertTrue(result.fills().isEmpty());
            assertEquals(ONE, result.canceledQuantity());
            assertTrue(book.isEmpty());
        }
    }

    @Nested
    @DisplayName("quote quantity")
    class QuoteQuantity {

        @Test
        @DisplayName("an exact budget fully fills at a single level")
        void exact_budget_fills_exactly() {
            book.submit(limit(1, SIDE_SELL, P_63250, ONE));

            // Spend exactly what one whole unit costs at the best ask.
            MatchResult result = book.submit(marketQuote(2, SIDE_BUY, P_63250));

            assertEquals(1, result.fills().size());
            assertEquals(ONE, result.filledQuantity());
            assertEquals(P_63250, result.fills().get(0).quoteQuantity());
            assertEquals(0, result.canceledQuantity());
            assertTrue(book.isEmpty());
        }

        @Test
        @DisplayName("a budget spanning two levels buys as much as it affords at each")
        void walks_multiple_levels() {
            book.submit(limit(1, SIDE_SELL, P_63250, ONE));
            book.submit(limit(2, SIDE_SELL, P_63300, ONE));

            // Enough to clear the first level entirely (costs P_63250) plus half of the
            // second (0.5 units at P_63300).
            long half = ONE / 2;
            long budget = P_63250 + Money.multiply(P_63300, half);
            MatchResult result = book.submit(marketQuote(3, SIDE_BUY, budget));

            assertEquals(2, result.fills().size());
            assertEquals(ONE, result.fills().get(0).quantity());
            assertEquals(P_63250, result.fills().get(0).price());
            assertEquals(half, result.fills().get(1).quantity());
            assertEquals(P_63300, result.fills().get(1).price());
            assertEquals(ONE + half, result.filledQuantity());
            assertEquals(0, result.canceledQuantity());   // budget spent exactly
            assertEquals(half, book.askDepth(1).get(0)[1]); // the rest of level two still rests
        }

        @Test
        @DisplayName("a budget too small to afford even one unit fills nothing, as dust")
        void unaffordable_budget_is_dust() {
            book.submit(limit(1, SIDE_SELL, P_63250, ONE));

            MatchResult result = book.submit(marketQuote(2, SIDE_BUY, 1L));

            assertTrue(result.fills().isEmpty());
            assertEquals(1L, result.canceledQuantity());
            assertEquals(P_63250, book.bestAsk());   // the resting order was never touched
        }

        @Test
        @DisplayName("running out of book liquidity cancels the unspent remainder")
        void exhausted_book_cancels_the_remainder() {
            book.submit(limit(1, SIDE_SELL, P_63250, ONE));

            MatchResult result = book.submit(marketQuote(2, SIDE_BUY, 2 * P_63250));

            assertEquals(ONE, result.filledQuantity());
            assertEquals(P_63250, result.canceledQuantity());   // half the budget, unspent
            assertTrue(book.isEmpty());
        }

        @Test
        @DisplayName("a budget landing mid-level fills one maker and partially fills the "
                + "next, at the same price")
        void fills_across_two_makers_at_the_same_level() {
            Order makerA = limit(2, SIDE_SELL, P_63250, ONE);
            Order makerB = limit(3, SIDE_SELL, P_63250, ONE);
            book.submit(makerA);
            book.submit(makerB);

            long half = ONE / 2;
            long budget = Money.multiply(P_63250, ONE + half);   // 1.5 units at this price

            MatchResult result = book.submit(marketQuote(1, SIDE_BUY, budget));

            assertEquals(2, result.fills().size());
            assertEquals(makerA.id(), result.fills().get(0).makerOrderId());
            assertEquals(ONE, result.fills().get(0).quantity());
            assertEquals(makerB.id(), result.fills().get(1).makerOrderId());
            assertEquals(half, result.fills().get(1).quantity());
            assertEquals(0, result.canceledQuantity());
            assertEquals(half, book.askDepth(1).get(0)[1]);   // B's remainder still rests
        }

        @Test
        @DisplayName("a sell-side quote-quantity order matches against the bid side symmetrically")
        void sell_side_quote_quantity_matches_against_bids() {
            book.submit(limit(1, SIDE_BUY, P_63250, ONE));

            MatchResult result = book.submit(marketQuote(2, SIDE_SELL, P_63250));

            assertEquals(1, result.fills().size());
            assertEquals(ONE, result.filledQuantity());
            assertEquals(0, result.canceledQuantity());
            assertTrue(book.isEmpty());
        }
    }

    @Nested
    @DisplayName("post-only")
    class PostOnly {

        @Test
        void limit_maker_is_rejected_when_it_would_cross() {
            book.submit(limit(1, SIDE_SELL, P_63250, ONE));

            MatchResult result = book.submit(
                    order(2, SIDE_BUY, ORDER_TYPE_LIMIT_MAKER, TIME_IN_FORCE_GTC, P_63300, ONE));

            assertTrue(result.isRejected());
            assertEquals(RejectReason.REJECT_REASON_WOULD_TAKE, result.rejection());
            assertEquals(ONE, book.askDepth(1).get(0)[1]);   // book untouched
        }

        @Test
        void limit_maker_rests_when_it_would_not_cross() {
            book.submit(limit(1, SIDE_SELL, P_63300, ONE));

            MatchResult result = book.submit(
                    order(2, SIDE_BUY, ORDER_TYPE_LIMIT_MAKER, TIME_IN_FORCE_GTC, P_63250, ONE));

            assertTrue(!result.isRejected());
            assertEquals(ONE, result.restingQuantity());
        }
    }

    @Nested
    @DisplayName("self-trade prevention")
    class SelfTrade {

        @Test
        @DisplayName("an unset mode never self-trades: it expires the maker, as EXPIRE_MAKER")
        void unset_mode_expires_the_maker() {
            Order own = limit(1, SIDE_SELL, P_63250, ONE);
            book.submit(own);

            MatchResult result = book.submit(limit(1, SIDE_BUY, P_63250, ONE,
                    SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_UNSPECIFIED));

            assertTrue(result.fills().isEmpty());
            assertEquals(own.id(), result.expiredMakers().get(0).id());
            assertEquals(ONE, result.restingQuantity());
        }


        @Test
        void expire_maker_withdraws_the_resting_order_and_matching_continues() {
            Order own = limit(1, SIDE_SELL, P_63250, ONE);
            Order other = limit(2, SIDE_SELL, P_63300, ONE);
            book.submit(own);
            book.submit(other);

            MatchResult result = book.submit(limit(1, SIDE_BUY, P_63300, ONE,
                    SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_MAKER));

            assertEquals(1, result.expiredMakers().size());
            Order expired = result.expiredMakers().get(0);
            // The whole order, not just an id -- everything OrderCanceled needs.
            assertEquals(own.id(), expired.id());
            assertEquals(1, expired.userId());
            assertEquals(P_63250, expired.price());
            assertEquals(SIDE_SELL, expired.side());
            assertEquals(ONE, expired.remaining());

            assertEquals(1, result.fills().size());
            assertEquals(other.id(), result.fills().get(0).makerOrderId());
            assertNull(book.find(own.id()));
            assertFalse(result.takerExpiredBySelfTrade());
        }

        @Test
        void own_orders_do_not_count_as_liquidity_for_fok_under_expire_maker() {
            book.submit(limit(1, SIDE_SELL, P_63250, 3 * ONE));   // same user

            MatchResult result = book.submit(
                    order(1, SIDE_BUY, ORDER_TYPE_LIMIT, TIME_IN_FORCE_FOK, P_63250, 3 * ONE,
                            SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_MAKER));

            assertTrue(result.isRejected());
            assertEquals(RejectReason.REJECT_REASON_INSUFFICIENT_LIQUIDITY, result.rejection());
        }

        @Test
        @DisplayName("EXPIRE_TAKER keeps prior fills but cancels the rest, and leaves the maker")
        void expire_taker_cancels_its_own_remainder() {
            Order otherMaker = limit(2, SIDE_SELL, P_63200, ONE);
            Order ownMaker = limit(1, SIDE_SELL, P_63250, ONE);
            book.submit(otherMaker);
            book.submit(ownMaker);

            MatchResult result = book.submit(order(1, SIDE_BUY, ORDER_TYPE_LIMIT,
                    TIME_IN_FORCE_GTC, P_63300, 2 * ONE,
                    SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_TAKER));

            assertEquals(1, result.fills().size());
            assertEquals(otherMaker.id(), result.fills().get(0).makerOrderId());
            assertTrue(result.expiredMakers().isEmpty(), "the maker is untouched, not cancelled");
            assertTrue(result.takerExpiredBySelfTrade());
            assertEquals(ONE, result.canceledQuantity());
            assertEquals(0, result.restingQuantity(), "cut short, so nothing rests");
            assertNotNull(book.find(ownMaker.id()), "the resting order survives");
        }

        @Test
        @DisplayName("FOK under EXPIRE_TAKER is rejected as SELF_TRADE when its own order "
                + "blocks liquidity past it, even after a partial amount from others")
        void fok_precheck_matches_where_expire_taker_actually_stops() {
            Order beforeSelf = limit(2, SIDE_SELL, P_63200, ONE);      // other user, reachable
            Order ownMaker = limit(1, SIDE_SELL, P_63250, ONE);        // same user: the wall
            Order afterSelf = limit(2, SIDE_SELL, P_63300, 5 * ONE);   // other user, NOT reachable
            book.submit(beforeSelf);
            book.submit(ownMaker);
            book.submit(afterSelf);

            // Naively summing all other-user liquidity (1 + 5) would say 6 is available,
            // enough for this 2-unit FOK -- but match() halts the instant it reaches
            // ownMaker, so only the 1 unit before it is ever actually reachable.
            MatchResult result = book.submit(order(1, SIDE_BUY, ORDER_TYPE_LIMIT,
                    TIME_IN_FORCE_FOK, P_63300, 2 * ONE,
                    SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_TAKER));

            assertTrue(result.isRejected());
            assertEquals(RejectReason.REJECT_REASON_SELF_TRADE, result.rejection());
            // Rejected before anything mutated -- the book is exactly as it was.
            assertNotNull(book.find(beforeSelf.id()));
            assertNotNull(book.find(ownMaker.id()));
            assertNotNull(book.find(afterSelf.id()));
        }

        @Test
        @DisplayName("FOK under EXPIRE_TAKER fills in full from others ahead of its own order")
        void fok_under_expire_taker_fills_before_reaching_its_own_order() {
            Order other = limit(2, SIDE_SELL, P_63200, 2 * ONE);
            Order ownMaker = limit(1, SIDE_SELL, P_63250, ONE);
            book.submit(other);
            book.submit(ownMaker);

            MatchResult result = book.submit(order(1, SIDE_BUY, ORDER_TYPE_LIMIT,
                    TIME_IN_FORCE_FOK, P_63250, 2 * ONE,
                    SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_TAKER));

            assertFalse(result.isRejected());
            assertEquals(2 * ONE, result.filledQuantity());
            assertFalse(result.takerExpiredBySelfTrade());
            assertNotNull(book.find(ownMaker.id()));
        }

        @Test
        @DisplayName("FOK under EXPIRE_TAKER with no own order in the way is short on liquidity, "
                + "not a self-trade")
        void fok_under_expire_taker_without_own_order_is_insufficient_liquidity() {
            book.submit(limit(2, SIDE_SELL, P_63250, ONE));

            MatchResult result = book.submit(order(1, SIDE_BUY, ORDER_TYPE_LIMIT,
                    TIME_IN_FORCE_FOK, P_63250, 2 * ONE,
                    SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_TAKER));

            assertEquals(RejectReason.REJECT_REASON_INSUFFICIENT_LIQUIDITY, result.rejection());
        }

        @Test
        @DisplayName("EXPIRE_BOTH cancels the maker AND stops the taker there")
        void expire_both_cancels_maker_and_taker() {
            Order ownMaker = limit(1, SIDE_SELL, P_63250, ONE);
            book.submit(ownMaker);

            MatchResult result = book.submit(order(1, SIDE_BUY, ORDER_TYPE_LIMIT,
                    TIME_IN_FORCE_GTC, P_63250, ONE,
                    SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_BOTH));

            assertTrue(result.fills().isEmpty());
            assertEquals(1, result.expiredMakers().size());
            assertEquals(ownMaker.id(), result.expiredMakers().get(0).id());
            assertTrue(result.takerExpiredBySelfTrade());
            assertEquals(ONE, result.canceledQuantity());
            assertNull(book.find(ownMaker.id()));
        }

        @Test
        @DisplayName("EXPIRE_TAKER cancels a quote-quantity market taker's whole remaining budget")
        void expire_taker_cancels_quote_quantity_remaining_budget() {
            Order ownMaker = limit(1, SIDE_SELL, P_63250, ONE);
            book.submit(ownMaker);

            long budget = 2 * P_63250;
            MatchResult result = book.submit(marketQuote(1, SIDE_BUY, budget,
                    SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_TAKER));

            assertTrue(result.fills().isEmpty());
            assertTrue(result.takerExpiredBySelfTrade());
            assertEquals(budget, result.canceledQuantity());   // the whole budget, in quote terms
            assertNotNull(book.find(ownMaker.id()), "the resting order survives");
        }

        @Test
        @DisplayName("EXPIRE_MAKER withdraws its own resting order and a quote-quantity "
                + "taker keeps going")
        void expire_maker_withdraws_for_quote_quantity_taker() {
            Order own = limit(1, SIDE_SELL, P_63250, ONE);
            Order other = limit(2, SIDE_SELL, P_63300, ONE);
            book.submit(own);
            book.submit(other);

            MatchResult result = book.submit(marketQuote(1, SIDE_BUY, P_63300,
                    SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_MAKER));

            assertEquals(1, result.expiredMakers().size());
            assertEquals(own.id(), result.expiredMakers().get(0).id());
            assertEquals(1, result.fills().size());
            assertEquals(other.id(), result.fills().get(0).makerOrderId());
            assertEquals(ONE, result.fills().get(0).quantity());
            assertEquals(0, result.canceledQuantity());
            assertTrue(book.isEmpty());
        }
    }

    @Nested
    @DisplayName("amend")
    class Amend {

        @Test
        @DisplayName("a smaller size at the same price keeps the order's place in the queue")
        void reducing_quantity_keeps_priority() {
            Order first = limit(1, SIDE_SELL, P_63250, 3 * ONE);
            Order second = limit(2, SIDE_SELL, P_63250, ONE);
            book.submit(first);
            book.submit(second);

            AmendResult result = book.amend(first.id(), null, ONE);

            assertTrue(result.priorityKept());
            assertEquals(3 * ONE, result.oldRemaining());
            assertEquals(ONE, result.newRemaining());
            assertEquals(2 * ONE, book.askDepth(1).get(0)[1]);
            MatchResult taker = book.submit(limit(3, SIDE_BUY, P_63250, ONE));
            assertEquals(first.id(), taker.fills().get(0).makerOrderId());
        }

        @Test
        @DisplayName("a larger size at the same price goes to the back of the queue")
        void increasing_quantity_loses_priority() {
            Order first = limit(1, SIDE_SELL, P_63250, ONE);
            Order second = limit(2, SIDE_SELL, P_63250, ONE);
            book.submit(first);
            book.submit(second);

            AmendResult result = book.amend(first.id(), null, 2 * ONE);

            assertFalse(result.priorityKept());
            assertEquals(3 * ONE, book.askDepth(1).get(0)[1]);
            MatchResult taker = book.submit(limit(3, SIDE_BUY, P_63250, ONE));
            assertEquals(second.id(), taker.fills().get(0).makerOrderId());
        }

        @Test
        @DisplayName("a new price that does not cross moves the order, and the old level is gone")
        void new_price_moves_the_order() {
            Order order = limit(1, SIDE_SELL, P_63300, ONE);
            book.submit(order);

            AmendResult result = book.amend(order.id(), P_63250, null);

            assertFalse(result.priorityKept());
            assertEquals(P_63300, result.oldPrice());
            assertEquals(P_63250, book.bestAsk());
            assertEquals(1, book.askDepth(10).size());
            assertEquals(ONE, result.match().restingQuantity());
            assertEquals(P_63250, book.find(order.id()).price());
        }

        @Test
        @DisplayName("a new price that crosses trades as a taker, at the maker's price, and the rest rests")
        void crossing_price_trades_as_taker() {
            Order maker = limit(2, SIDE_SELL, P_63250, ONE);
            Order order = limit(1, SIDE_BUY, P_63200, 3 * ONE);
            book.submit(maker);
            book.submit(order);

            AmendResult result = book.amend(order.id(), P_63300, null);

            assertEquals(1, result.match().fills().size());
            assertEquals(maker.id(), result.match().fills().get(0).makerOrderId());
            assertEquals(P_63250, result.match().fills().get(0).price());
            assertEquals(2 * ONE, result.match().restingQuantity());
            assertEquals(P_63300, book.bestBid());
            assertNull(book.bestAsk());
        }

        @Test
        @DisplayName("the filled part stays filled: the new total only changes what is left")
        void new_total_includes_the_filled_part() {
            Order order = limit(1, SIDE_SELL, P_63250, 3 * ONE);
            book.submit(order);
            book.submit(limit(2, SIDE_BUY, P_63250, ONE));          // fills 1 of 3

            AmendResult result = book.amend(order.id(), null, 2 * ONE);

            assertEquals(2 * ONE, result.oldRemaining());
            assertEquals(ONE, result.newRemaining());
            assertEquals(ONE, book.find(order.id()).filled());
            assertEquals(ONE, book.askDepth(1).get(0)[1]);
        }

        @Test
        @DisplayName("a new total not above what already filled is refused and the order is untouched")
        void quantity_not_above_filled_is_refused() {
            Order order = limit(1, SIDE_SELL, P_63250, 3 * ONE);
            book.submit(order);
            book.submit(limit(2, SIDE_BUY, P_63250, ONE));

            AmendResult result = book.amend(order.id(), null, ONE);

            assertEquals(AmendRejectReason.AMEND_REJECT_REASON_QUANTITY_NOT_ABOVE_FILLED,
                    result.rejection());
            assertEquals(2 * ONE, book.find(order.id()).remaining());
        }

        @Test
        @DisplayName("a post-only order whose new price would cross is refused and keeps its place")
        void limit_maker_crossing_is_refused() {
            Order maker = limit(2, SIDE_SELL, P_63250, ONE);
            Order postOnly = order(1, SIDE_BUY, ORDER_TYPE_LIMIT_MAKER, TIME_IN_FORCE_GTC, P_63200, ONE);
            book.submit(maker);
            book.submit(postOnly);

            AmendResult result = book.amend(postOnly.id(), P_63250, null);

            assertEquals(AmendRejectReason.AMEND_REJECT_REASON_WOULD_TAKE, result.rejection());
            assertEquals(P_63200, book.find(postOnly.id()).price());
            assertNotNull(book.find(maker.id()));
        }

        @Test
        @DisplayName("a post-only order can still move to a price that does not cross")
        void limit_maker_can_move_without_crossing() {
            book.submit(limit(2, SIDE_SELL, P_63300, ONE));
            Order postOnly = order(1, SIDE_BUY, ORDER_TYPE_LIMIT_MAKER, TIME_IN_FORCE_GTC, P_63200, ONE);
            book.submit(postOnly);

            assertFalse(book.amend(postOnly.id(), P_63250, null).isRejected());
            assertEquals(P_63250, book.bestBid());
        }

        @Test
        @DisplayName("a crossing amend obeys the order's own self-trade prevention mode")
        void crossing_amend_applies_self_trade_prevention() {
            Order own = limit(1, SIDE_SELL, P_63250, ONE);
            Order order = limit(1, SIDE_BUY, P_63200, ONE,
                    SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_TAKER);
            book.submit(own);
            book.submit(order);

            AmendResult result = book.amend(order.id(), P_63250, null);

            assertTrue(result.match().fills().isEmpty());
            assertTrue(result.match().takerExpiredBySelfTrade());
            assertNull(book.find(order.id()));
            assertNotNull(book.find(own.id()));
        }

        @Test
        @DisplayName("an order that is already gone is refused, not an error")
        void unknown_order_is_refused() {
            assertEquals(AmendRejectReason.AMEND_REJECT_REASON_ORDER_NOT_FOUND,
                    book.amend(UUID.randomUUID(), P_63250, null).rejection());
        }
    }

    @Nested
    @DisplayName("cancel")
    class Cancel {

        @Test
        void cancelling_removes_the_order_and_its_empty_level() {
            Order order = limit(1, SIDE_BUY, P_63250, ONE);
            book.submit(order);

            assertNotNull(book.cancel(order.id()));
            assertNull(book.bestBid());
            assertTrue(book.isEmpty());
        }

        @Test
        @DisplayName("cancelling an already-filled order is a no-op, not an error")
        void cancel_racing_a_fill() {
            Order maker = limit(1, SIDE_SELL, P_63250, ONE);
            book.submit(maker);
            book.submit(limit(2, SIDE_BUY, P_63250, ONE));   // fills it

            assertNull(book.cancel(maker.id()));
        }

        @Test
        void cancelling_a_partially_filled_order_removes_only_the_remainder() {
            Order maker = limit(1, SIDE_SELL, P_63250, 3 * ONE);
            book.submit(maker);
            book.submit(limit(2, SIDE_BUY, P_63250, ONE));

            Order canceled = book.cancel(maker.id());

            assertEquals(2 * ONE, canceled.remaining());
            assertEquals(ONE, canceled.filled());
            assertTrue(book.isEmpty());
        }

        @Test
        void cancel_all_takes_only_that_users_orders() {
            book.submit(limit(1, SIDE_BUY, P_63250, ONE));
            book.submit(limit(1, SIDE_BUY, P_63200, ONE));
            Order other = limit(2, SIDE_BUY, P_63200, ONE);
            book.submit(other);

            List<Order> removed = book.cancelAllFor(1);

            assertEquals(2, removed.size());
            assertEquals(1, book.restingOrderCount());
            assertNotNull(book.find(other.id()));
        }

        @Test
        void cancel_all_for_a_user_with_nothing_here_removes_nothing() {
            book.submit(limit(2, SIDE_BUY, P_63200, ONE));

            assertTrue(book.cancelAllFor(1).isEmpty());
            assertEquals(1, book.restingOrderCount());
        }

        @Test
        void a_filtered_cancel_all_takes_only_the_matching_side() {
            Order buy = limit(1, SIDE_BUY, P_63200, ONE);
            Order sell = limit(1, SIDE_SELL, P_63300, ONE);
            book.submit(buy);
            book.submit(sell);

            List<Order> removed = book.cancelAllFor(1, SIDE_BUY, null);

            assertEquals(List.of(buy), removed);
            assertNotNull(book.find(sell.id()));
        }

        @Test
        void a_filtered_cancel_all_takes_only_the_matching_type() {
            Order plain = limit(1, SIDE_BUY, P_63200, ONE);
            Order postOnly = order(1, SIDE_BUY, ORDER_TYPE_LIMIT_MAKER, TIME_IN_FORCE_GTC, P_63200, ONE);
            book.submit(plain);
            book.submit(postOnly);

            List<Order> removed = book.cancelAllFor(1, null, ORDER_TYPE_LIMIT_MAKER);

            assertEquals(List.of(postOnly), removed);
            assertNotNull(book.find(plain.id()));
        }

        /** Both filters are AND-ed, so an order that matches only one of them stays. */
        @Test
        void a_filtered_cancel_all_needs_every_filter_to_match() {
            Order buyMaker = order(1, SIDE_BUY, ORDER_TYPE_LIMIT_MAKER, TIME_IN_FORCE_GTC, P_63200, ONE);
            Order sellMaker = order(1, SIDE_SELL, ORDER_TYPE_LIMIT_MAKER, TIME_IN_FORCE_GTC, P_63300, ONE);
            Order buyPlain = limit(1, SIDE_BUY, P_63200, ONE);
            book.submit(buyMaker);
            book.submit(sellMaker);
            book.submit(buyPlain);

            assertEquals(List.of(buyMaker), book.cancelAllFor(1, SIDE_BUY, ORDER_TYPE_LIMIT_MAKER));
            assertEquals(2, book.restingOrderCount());
        }

        /** Cancel-all reads a per-owner view of the book. These three are every way an order
         *  can leave without {@code cancel} being called -- each one a chance for that view to
         *  keep a ghost the book itself no longer has. */
        @Test
        void a_filled_order_is_gone_from_its_owners_cancel_all() {
            Order maker = limit(1, SIDE_SELL, P_63250, ONE);
            book.submit(maker);
            book.submit(limit(2, SIDE_BUY, P_63250, ONE));   // fills it

            assertTrue(book.cancelAllFor(1).isEmpty());
            assertTrue(book.isEmpty());
        }

        @Test
        void a_self_trade_expired_maker_is_gone_from_its_owners_cancel_all() {
            Order maker = limit(1, SIDE_SELL, P_63250, ONE,
                    SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_MAKER);
            book.submit(maker);
            // IOC, so the taker that expired it does not rest and leaves the book empty.
            book.submit(order(1, SIDE_BUY, ORDER_TYPE_LIMIT, TIME_IN_FORCE_IOC, P_63250, ONE,
                    SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_MAKER));

            assertTrue(book.cancelAllFor(1).isEmpty());
            assertTrue(book.isEmpty());
        }

        @Test
        void an_amended_order_is_cancelled_all_under_its_new_identity() {
            Order original = limit(1, SIDE_BUY, P_63200, ONE);
            book.submit(original);

            // A new price replaces the order rather than keeping its queue place.
            book.amend(original.id(), P_63250, null);

            List<Order> removed = book.cancelAllFor(1);
            assertEquals(1, removed.size());
            assertEquals(P_63250, removed.get(0).price());
            assertTrue(book.isEmpty());
        }
    }

    @Nested
    @DisplayName("determinism")
    class Determinism {

        @Test
        @DisplayName("the same orders in the same order produce the identical book")
        void replay_reproduces_the_book() {
            List<Order> script = List.of(
                    limit(1, SIDE_BUY, P_63200, ONE),
                    limit(2, SIDE_SELL, P_63300, 2 * ONE),
                    limit(3, SIDE_BUY, P_63250, 3 * ONE),
                    limit(4, SIDE_SELL, P_63250, ONE));

            OrderBook a = new OrderBook("BTCUSDT");
            OrderBook b = new OrderBook("BTCUSDT");
            for (Order o : script) {
                a.submit(copy(o));
                b.submit(copy(o));
            }

            assertEquals(a.bestBid(), b.bestBid());
            assertEquals(a.bestAsk(), b.bestAsk());
            assertEquals(a.restingOrderCount(), b.restingOrderCount());
            assertEquals(a.bidDepth(10).size(), b.bidDepth(10).size());
            for (int i = 0; i < a.bidDepth(10).size(); i++) {
                assertEquals(a.bidDepth(10).get(i)[0], b.bidDepth(10).get(i)[0]);
                assertEquals(a.bidDepth(10).get(i)[1], b.bidDepth(10).get(i)[1]);
            }
        }

        private static Order copy(Order o) {
            return new Order(o.id(), o.userId(), o.side(), o.type(),
                    o.timeInForce(), o.price(), o.quantity());
        }
    }
}
