package com.exchange.bff.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The account a request trades or reads as: the signed-in customer's own, unless the request
 * names their lead account ({@link TradingAccounts}). Used in place of {@link CurrentUser}
 * only where an order, a fill or a balance is the account's and not the customer's --
 * everything else about a customer stays their own, whichever account the screen is showing.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface TradingAccount {
}
