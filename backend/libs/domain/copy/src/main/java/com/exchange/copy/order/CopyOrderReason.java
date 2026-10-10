package com.exchange.copy.order;

import com.exchange.spot.order.exception.EngineUnavailableException;
import com.exchange.spot.order.exception.MarketOrdersDisabledException;
import com.exchange.spot.order.exception.MarketUnavailableException;
import com.exchange.spot.order.exception.OrderLimitExceededException;
import com.exchange.spot.order.exception.SymbolNotTradingException;
import com.exchange.wallet.balance.exception.InsufficientBalanceException;
import java.util.Optional;

/** Why a copy failed or was skipped: what the follower's page says, and what a leader's success rate counts. */
public enum CopyOrderReason {
    /** A sell with nothing held: the buy was missed. Neither a failure nor a success. */
    NOTHING_TO_SELL,
    INSUFFICIENT_BALANCE,
    /** Smaller than the market takes, once scaled to the follower. */
    BELOW_MINIMUM,
    /** The market is not taking orders, or not this kind. */
    MARKET_CLOSED,
    ORDER_LIMIT,
    /** The follower, or their master, may not trade. */
    NOT_ELIGIBLE,
    /** Placed and closed with nothing filled: a buy that could not fill inside the cap. */
    NOT_FILLED,
    /** The engine refused it. */
    REJECTED,
    /** The order path refused it for a reason with no name of its own here. */
    REFUSED,
    /** No engine was running the market when the copy came up: skipped, not failed. The
     *  leader's own order is waiting on the same engine. */
    MARKET_PAUSED;

    /**
     * The reason an exception from placing an order stands for, or empty if it is not a refusal
     * at all -- a database that is down is not the follower's failed copy, and must be retried.
     */
    public static Optional<CopyOrderReason> ofRefusal(RuntimeException e) {
        if (e instanceof InsufficientBalanceException) {
            return Optional.of(INSUFFICIENT_BALANCE);
        }
        if (e instanceof SymbolNotTradingException || e instanceof MarketOrdersDisabledException
                || e instanceof MarketUnavailableException) {
            return Optional.of(MARKET_CLOSED);
        }
        if (e instanceof EngineUnavailableException) {
            return Optional.of(MARKET_PAUSED);
        }
        if (e instanceof OrderLimitExceededException) {
            return Optional.of(ORDER_LIMIT);
        }
        String thrownFrom = e.getClass().getPackageName();
        return thrownFrom.equals(SymbolNotTradingException.class.getPackageName())
                ? Optional.of(REFUSED) : Optional.empty();
    }
}
