package com.exchange.spot.order.exception;

/** The field exists but cannot change on this order: docs/reference/order-types.md has the
 *  table. A trailing order takes a new size only, as everywhere. */
public class FieldNotAmendableException extends RuntimeException {

    public FieldNotAmendableException(String field, String why) {
        super(field + " cannot be amended: " + why);
    }
}
