package com.exchange.spot.openapi.ws;

import java.time.Instant;

/**
 * One connection's allowance of inbound messages: {@code perSecond} in any one second, and the
 * connection is dropped once it has been over the limit for {@code graceSeconds} in a row.
 *
 * <p>A fixed one-second window, like {@code RateLimiter}'s, and in memory rather than Redis: a
 * message budget is per connection, and a connection lives on one instance. Not thread-safe by
 * design -- a socket's inbound frames arrive on one thread.
 */
final class InboundBudget {

    private final int perSecond;
    private final int graceSeconds;
    private long windowSecond = Long.MIN_VALUE;
    private int inWindow;
    private boolean exceededInWindow;
    /** The first second of the current run of over-limit seconds; -1 while under. */
    private long overSince = -1;

    InboundBudget(int perSecond, int graceSeconds) {
        this.perSecond = perSecond;
        this.graceSeconds = graceSeconds;
    }

    enum Verdict { ALLOW, REFUSE, DISCONNECT }

    Verdict onMessage(Instant now) {
        long second = now.getEpochSecond();
        if (second != windowSecond) {
            // A whole second under the limit, or a gap of silence, ends the run. A second that
            // merely opened with an allowed message does not: the run counts seconds over, in a row.
            if (!exceededInWindow || second - windowSecond > 1) {
                overSince = -1;
            }
            windowSecond = second;
            inWindow = 0;
            exceededInWindow = false;
        }
        inWindow++;
        if (inWindow <= perSecond) {
            return Verdict.ALLOW;
        }
        exceededInWindow = true;
        if (overSince < 0) {
            overSince = second;
        }
        return second - overSince >= graceSeconds ? Verdict.DISCONNECT : Verdict.REFUSE;
    }
}
