# Spot order guide

## Parameters

| Parameter | Type | Required | Notes |
|---|---|---|---|
| `symbol` | string | yes | must be a `TRADING` symbol |
| `side` | `BUY` / `SELL` | yes | |
| `orderType` | enum | yes | `LIMIT`, `MARKET`, `STOP_LOSS`, `STOP_LOSS_LIMIT`, `TAKE_PROFIT`, `TAKE_PROFIT_LIMIT`, `LIMIT_MAKER` |
| `timeInForce` | `GTC` / `IOC` / `FOK` | required, except on `MARKET`, which is always `IOC` | |
| `price` | decimal | limit family only (`LIMIT`, `LIMIT_MAKER`, `STOP_LOSS_LIMIT`, `TAKE_PROFIT_LIMIT`) | must be on the symbol's price tick |
| `stopPrice` | decimal | conditional types, unless `trailingDeltaBps` is set | the trigger price. When trailing, an optional activation price instead |
| `quantity` | decimal | every type except a market-firing BUY | size in the base asset. Refused on a market-firing BUY |
| `quoteOrderQty` | decimal | market-firing BUY only | budget in the quote asset. Refused everywhere else |
| `trailingDeltaBps` | int, 10 to 2000 | optional, conditional types only | basis points the market must reverse from its extreme before the order fires |
| `clientOrderId` | string | optional | idempotency key. Sending it again returns the original order's id instead of creating a new order |
| `selfTradePreventionMode` | enum | optional, default `EXPIRE_MAKER` | `EXPIRE_TAKER`, `EXPIRE_MAKER`, `EXPIRE_BOTH`. No mode allows a self-trade: settlement records a trade as one buyer and one seller. `EXPIRE_BOTH` is refused with `FOK`, which must leave the book untouched when it cannot fill |

"Market-firing" means `MARKET`, `STOP_LOSS` and `TAKE_PROFIT`. Each fires as a market order once
triggered. Plain `MARKET` fires immediately. The `_LIMIT` conditional types fire as a limit order
at `price` instead, so they always take `quantity`, never `quoteOrderQty`.

The response is `202 Accepted` with `{ orderId }`. Funds are locked and the order is durably
queued at that point, but matching happens later. The fill or the rejection arrives afterwards.

## Sizing

| Type | Side | Sizing | Engine | web-bff | open-api |
|---|---|---|:-:|:-:|:-:|
| LIMIT | BUY | quantity | ✓ | ✓ | ✓ |
| LIMIT | SELL | quantity | ✓ | ✓ | ✓ |
| LIMIT_MAKER | BUY | quantity | ✓ | ✓ | ✓ |
| LIMIT_MAKER | SELL | quantity | ✓ | ✓ | ✓ |
| MARKET | BUY | quantity | ✗ | ✗ | ✗ |
| MARKET | BUY | quoteOrderQty | ✓ | ✓ | ✓ |
| MARKET | SELL | quantity | ✓ | ✓ | ✓ |
| MARKET | SELL | quoteOrderQty | ✗ | ✗ | ✗ |
| STOP_LOSS | BUY | quantity | ✗ | ✗ | ✗ |
| STOP_LOSS | BUY | quoteOrderQty | ✓ | ✓ | ✓ |
| STOP_LOSS | SELL | quantity | ✓ | ✓ | ✓ |
| STOP_LOSS | SELL | quoteOrderQty | ✗ | ✗ | ✗ |
| TAKE_PROFIT | BUY | quantity | ✗ | ✗ | ✗ |
| TAKE_PROFIT | BUY | quoteOrderQty | ✓ | ✓ | ✓ |
| TAKE_PROFIT | SELL | quantity | ✓ | ✓ | ✓ |
| TAKE_PROFIT | SELL | quoteOrderQty | ✗ | ✗ | ✗ |
| STOP_LOSS_LIMIT | BUY | quantity | ✓ | ✓ | ✓ |
| STOP_LOSS_LIMIT | SELL | quantity | ✓ | ✓ | ✓ |
| TAKE_PROFIT_LIMIT | BUY | quantity | ✓ | ✓ | ✓ |
| TAKE_PROFIT_LIMIT | SELL | quantity | ✓ | ✓ | ✓ |

One rule, enforced the same way in all three places: the asset a side gives up is the only one
that can be locked exactly before matching. A BUY gives up quote. Sized by `quantity`, its
quote cost has no upper bound if the price is worse than expected, so `quoteOrderQty` is
required. A SELL gives up base. Sized by `quoteOrderQty`, its base cost has no upper bound in
the same way, because it walks deeper into the book to reach the same proceeds, so plain
`quantity` is required. Sending both, or the wrong one, is rejected. It is never silently
corrected or defaulted.

## Bounds

Checked once, at placement, in `OrderService`. The engine trusts them. The columns are on
`spot_symbols`.

| Order | Checked | Columns |
|---|---|---|
| Limit family | `quantity` min, max and step, then `price × quantity` against the notional min and max | `min_qty`, `max_qty`, `qty_step`, `min_notional`, `max_notional` |
| Market-firing SELL | `quantity` min, max and step | `market_min_qty`, `market_max_qty`, `market_qty_step`. Each falls back to the limit column when null |
| Market-firing BUY | `quoteOrderQty` min and max | `min_quote_qty`, `max_quote_qty`. Null means no bound |

`market_max_qty` is set well below `max_qty` on every seeded market. A limit order for the
limit maximum rests on the book. A market order for it sweeps the whole book. There is no
notional check on a market sell, because there is no price until it matches, so `market_min_qty`
is where the dust floor for market sells lives. The seed sets it to clear 10 USDT at a price well
below today's.

## Trigger conditions

Stop and take-profit orders watch the last traded price only, never the best bid or ask. They
fire the moment it crosses `stopPrice` in the direction below. All comparisons are inclusive.

| Type | Side | Fires when the last trade price |
|---|---|---|
| STOP_LOSS / STOP_LOSS_LIMIT | SELL | falls to or below `stopPrice` |
| STOP_LOSS / STOP_LOSS_LIMIT | BUY | rises to or above `stopPrice` |
| TAKE_PROFIT / TAKE_PROFIT_LIMIT | SELL | rises to or above `stopPrice` |
| TAKE_PROFIT / TAKE_PROFIT_LIMIT | BUY | falls to or below `stopPrice` |

Once fired, `STOP_LOSS` and `TAKE_PROFIT` become a `MARKET` order (IOC). The `_LIMIT` types become
a `LIMIT` order at their `price`. A fired stop's own fill can move the price enough to fire
another stop in the same cascade.

**Immediate trigger.** An order is rejected, not fired, if its trigger condition is already met
at placement, for example a `STOP_LOSS SELL` placed with `stopPrice` above the current market. A
symbol that has never traded rejects every conditional order too, since there is no last trade
price to compare against.

**Trailing stops.** Set `trailingDeltaBps` instead of, or together with, `stopPrice`:

- With `stopPrice`: it is an activation price. The order sits still until the market first
  reaches it, then tracking starts.
- Without `stopPrice`: tracking starts immediately at the current market price.

Once tracking, the engine follows the best price seen since activation: the highest for
`STOP_LOSS SELL` and `TAKE_PROFIT BUY`, the lowest for `STOP_LOSS BUY` and `TAKE_PROFIT SELL`. The
effective stop trails `trailingDeltaBps` behind it. That tracked extreme only ever moves in your
favour, never back toward the trigger. So the effective stop tightens as the market moves your
way and holds still on a pullback, until the pullback is big enough to fire it.

## Amending

`PATCH` on an open order changes it in place. Every field is optional, and an absent one keeps
its value. The answer is `202`. The outcome is an `OrderAmended` or `OrderAmendRejected` event,
and until it arrives the order is `amendPending` and a second amend is refused.

| Order | Can change | Engine refuses when |
|---|---|---|
| Resting `LIMIT` | `price`, `quantity` | never. A price that crosses trades as a taker, under the order's own self-trade prevention mode |
| Resting `LIMIT_MAKER` | `price`, `quantity` | the new price would cross (`WOULD_TAKE`) |
| Parked conditional | `stopPrice`, `price` on the `_LIMIT` types, size | the new `stopPrice` already holds (`WOULD_TRIGGER_IMMEDIATELY`) |
| Parked trailing, tracking or not | size only | anything else is sent (`FIELD_NOT_AMENDABLE`) |
| `MARKET`, IOC, FOK, terminal | nothing | the API refuses first |

- **`quantity` is the new total**, filled part included, as everywhere. A fill racing
  the amend then cannot change what was meant. It must stay above what has filled: the API
  checks its row, and the engine checks again (`QUANTITY_NOT_ABOVE_FILLED`).
- Size is `quoteOrderQty` on an order that was placed with one.
- **Queue priority** survives one change only: a smaller `quantity` at the same price. Any other
  amend gives the order terms nobody has had a chance to trade against, so it goes to the back.
- A trailing order's trigger is derived from the market. Nowhere lets it be
  edited in place, and neither do we: cancel it and place a new one.
- A refused amend leaves the order exactly as it was.
- The new terms pass the same tick, step, quantity and notional checks as a placement.

