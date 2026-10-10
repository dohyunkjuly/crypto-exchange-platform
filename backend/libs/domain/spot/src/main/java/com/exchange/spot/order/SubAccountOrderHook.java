package com.exchange.spot.order;

import com.exchange.spot.symbol.SpotSymbol;
import java.util.UUID;

/**
 * What else a sub-account's order must pass, and leave behind, as it is placed. The order path
 * knows a sub only by its id; what the account is for belongs to whoever implements this
 * (copy trading: docs/reference/copy-trading.md).
 *
 * <p>Called for a sub-account's order only, so a master's order -- nearly all of them -- pays
 * nothing for it. An app that places orders and leaves this unwired places them unchecked.
 */
public interface SubAccountOrderHook {

    /**
     * In the placing transaction, after the order is valid and before its funds are locked, so
     * the balance read here is the one the order is about to draw on. Throwing refuses the order.
     *
     * @param lockAmount what is about to be locked: the cost in the quote asset for a buy, the
     *                   quantity of the base asset for a sell
     * @param quantity   the order's quantity in the base asset; zero for one sized in the quote asset
     * @throws ManualOrderNotAllowedException the account is not one anybody trades by hand
     * @throws MarketNotAllowedException the account may not trade this market
     */
    void beforeLock(UUID orderId, Long userId, SpotSymbol market, Side side, long lockAmount, long quantity);
}
