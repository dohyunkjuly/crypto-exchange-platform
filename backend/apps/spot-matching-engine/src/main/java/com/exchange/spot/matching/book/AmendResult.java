package com.exchange.spot.matching.book;

import com.exchange.contracts.spot.v1.AmendRejectReason;

/**
 * What an amend did to a resting order.
 *
 * @param rejection       non-null if refused, and then the book is untouched and nothing else
 *                        here is set
 * @param order           the order as it now stands -- a new instance if it was re-queued
 * @param oldPrice        its price before
 * @param oldRemaining    what was left of it before
 * @param newRemaining    what was left right after the amend, before any matching
 * @param priorityKept    true for a size reduction at the same price, the one change that
 *                        keeps its place in the queue
 * @param match           what happened when a re-queued order went back through matching;
 *                        null when priority was kept, since nothing could have traded
 */
public record AmendResult(AmendRejectReason rejection, Order order, long oldPrice,
                          long oldRemaining, long newRemaining, boolean priorityKept,
                          MatchResult match) {

    static AmendResult rejected(AmendRejectReason reason) {
        return new AmendResult(reason, null, 0, 0, 0, false, null);
    }

    public boolean isRejected() {
        return rejection != null;
    }
}
