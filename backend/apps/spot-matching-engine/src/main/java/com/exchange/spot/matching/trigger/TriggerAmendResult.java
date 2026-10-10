package com.exchange.spot.matching.trigger;

import com.exchange.contracts.spot.v1.AmendRejectReason;

/**
 * What an amend did to a parked order. {@code before} and {@code after} are both null when
 * the order is not parked here at all, which is not a refusal: the caller tries the book next.
 *
 * @param rejection non-null if refused, and then the order is untouched
 */
public record TriggerAmendResult(AmendRejectReason rejection, PendingOrder before,
                                 PendingOrder after) {

    static final TriggerAmendResult NOT_PARKED = new TriggerAmendResult(null, null, null);

    static TriggerAmendResult rejected(AmendRejectReason reason, PendingOrder order) {
        return new TriggerAmendResult(reason, order, null);
    }

    public boolean isParked() {
        return before != null;
    }

    public boolean isRejected() {
        return rejection != null;
    }
}
