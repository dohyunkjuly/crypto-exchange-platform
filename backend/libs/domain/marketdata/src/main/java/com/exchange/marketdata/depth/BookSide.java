package com.exchange.marketdata.depth;

/**
 * Which half of the book a level belongs to.
 *
 * <p>Deliberately not the wire's {@code Side}. That enum is an <em>order's</em> direction
 * -- what someone wanted to do -- and the two are not the same thing at the one place it
 * matters: a trade removes liquidity from the <b>maker's</b> side, which is the opposite
 * of the taker side the event reports. Keeping a separate type means that conversion has
 * to be written down once, deliberately, rather than being a field copied across because
 * the names lined up.
 *
 * <p>It also keeps {@code libs/contracts} out of this module. Depth is read by both apps
 * and neither of them parses protobuf.
 */
public enum BookSide {

    /** Bids: what buyers are offering. Ordered highest price first -- the best bid is the
     *  most anyone will pay. */
    BID,

    /** Asks: what sellers are asking. Ordered lowest price first -- the best ask is the
     *  least anyone will take. */
    ASK
}
