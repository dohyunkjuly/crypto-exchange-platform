# Locked funds

An open order must never hold less than its terms need, and must hold nothing once it is done.
Every movement of a lock has one owner:

| Moment | Who | What |
|---|---|---|
| Placement | the API, before publishing | locks `quantity` of base (sell), `price × quantity` of quote (buy), or the quote budget (quote-sized buy). `4001` if short; nothing is published. The lock, the row and the command commit together; the publish follows the commit ([delivery-guarantees.md](delivery-guarantees.md)) |
| Fill | settlement, per trade | `SETTLE_LOCKED` takes what was actually paid or delivered |
| Fill of a buying taker below its limit | settlement, per trade | releases `limit × quantity − quote_quantity`, from `TradeExecuted.taker_limit_price` |
| Cancel or reject | settlement | releases what the unfilled part holds, `OrderLock.amount` from the row |
| Amend that needs more | the API, before publishing | locks the extra, sized for the worst fill that can land meanwhile (`OrderLock.additionalFor`) |
| Amend applied or refused | settlement | releases the surplus, computed from the event alone |

`OrderLock` in `libs/domain/spot` is the one formula for what an order holds. The API and
settlement must agree to the unit, or funds stay locked forever or are released twice.

## Why a lock is never freed and re-locked

An amend could free the old lock and take a new one, but between those two steps the order
would hold nothing and the user could spend the funds elsewhere. So only the difference moves,
one direction at a time: more is locked by the API before the command leaves, less is released
by settlement after the engine confirms.

The API sizes the extra from a row that may be stale, because fills land while the amend is in
flight. The extra needed is `new(f) − old(f)` for the filled amount `f` at that moment, which is
linear in `f` and grows with it when the price drops and the size grows, so the row's own
filled amount is not always the worst case: `additionalFor` tries both ends. Settlement then
releases `held by the old terms + additional_locked − needed by the new terms`, all from the
event's own old and new price and remaining quantity, so it is right wherever the order's fills
fall in the batch. On a refusal it releases `additional_locked` whole.

**One amend per order at a time.** `spot_orders.amend_pending` is claimed by the API with a
conditional `UPDATE` and cleared by settlement. Two amends computed from the same stale row can
leave the order short: the first lowers the price and settlement releases the difference; the
second "restores" the old price and, seeing no change against the row it read, locks nothing.

## Price improvement

A buying taker locks its own limit per unit and pays the maker's price, which can be lower. The
difference is released with the fill, keyed on the trade id, because nothing else would ever
release it: a cancel returns only what the unfilled part holds, and a `FILLED` order is never
looked at again. A selling taker delivers exactly the base it locked, a maker fills at its own
price, and a quote-sized order locks a budget; none has anything to release.

## Idempotency

Every lock and release carries an idempotency key (`order-lock:<orderId>`,
`order-amend-lock:<commandId>`, `trade:<tradeId>:buyer:improvement-unlock`,
`order:<orderId>:amend:<commandId>-unlock`, …), so a redelivered batch or a retried request
moves nothing twice ([settlement-pipeline.md](settlement-pipeline.md)).
