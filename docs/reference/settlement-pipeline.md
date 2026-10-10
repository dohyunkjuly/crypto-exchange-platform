# The settlement pipeline

`spot-settlement` is the only app that writes order and wallet state from the trading event
stream. Nothing else may touch `spot_orders` or `wallet_balances` because of a trade. That is
how two apps can never settle the same fill twice.

## Interfaces

- **Kafka in:** `exchange.spot.events.v1`, carrying `TradeExecuted`, `OrderRejected`,
  `OrderCanceled` and `OrderAccepted`. The last one does nothing here. The order was already
  saved correctly when it was placed.
- **MySQL out:** `spot_orders`, `spot_trades`, `spot_order_fill_log`, `wallet_balances`,
  `wallet_ledger_entries`.
- No HTTP except a health check. This app has no API of its own.

## The pipeline, per batch

Kafka hands this app a batch of events at a time, up to `max.poll.records`, not one event at a
time. Everything below happens once per batch, before that batch's offset is acknowledged.

1. **Parse.** A record that cannot be parsed is logged and skipped. Retrying can never fix it,
   so it must not block the rest of the batch forever.
2. **Resolve symbols.** Every distinct symbol in the batch is looked up once, up front.
3. **Amends, before any fill.** `OrderAmended` rewrites the order's price, stop price and
   quantity. It has to come first: a fill that follows an amend to a larger size would be
   refused as an overfill against the old one. It is safe for the fills that came before it
   too, because the engine only accepts a new total above what had filled. `OrderAmendRejected`
   changes nothing but clears `amend_pending`.
4. **Record trades.** Every symbol's trades become new `spot_trades` rows, written in one round
   trip per symbol rather than one per trade. This runs alone, because what it
   returns is the list of wallet movements step 5 works from.
5. **Fills and trade-driven balances, together.** Every order's fills, summed across however many
   trades touched it and applied as one update to that row, and every `(user, asset)` balance
   group are dispatched at the same time. They touch different tables, orders and the fill log
   on one side, balances and the ledger on the other, so there is no shared row to wait on. The
   balance side is always the slower one, and this way it no longer waits behind every fill.
6. **Rejects and cancels, last.** These read an order's current state to work out how much to
   unlock, so they must come after every fill in the batch that could touch the same order.
   That only constrains them relative to fills, not to each other. Two different orders' cancels
   never share a row, so they are dispatched together like everything else. Their unlock
   movements are the one piece of balance work that cannot ride along with step 5, because they
   do not exist until this step has run. They are applied last, on their own.
7. Only once all of that has succeeded is the batch acknowledged.

## Locked funds

What each step locks or releases, and why an amend never frees and re-locks, is in
[locked-funds.md](locked-funds.md). Every release here is computed either from the event
alone (amends, price improvement) or from the row after every fill in the batch has been
applied (cancels and rejects), which is what fixes the step order above.

## Why group instead of one write per trade

The database can still only write one row at a time, however many events arrive at once.
Grouping means a repeat participant, such as a large order matched against many counterparties
or a trader whose wallet is touched by many trades in one batch, pays for one write instead of
one per trade. A participant touched once pays the same either way. It is a single write.

## Every group is independent and safe to run in parallel

Different groups are dispatched together, not one after another. This is safe by construction,
because two different groups, meaning different orders or different `(user, asset)` pairs, never
touch the same row. Real concurrency is capped by the database connection pool, not by how many
groups exist. However many connections are configured is how many groups can be mid-write at
once.

This applies to cancels too, and that was measured rather than assumed. The reject and cancel
phase stayed sequential for a while after everything else was parallel, on the assumption that
fills would always dominate. Against real infrastructure, a batch of many cancels, as in a quiet
market where liquidity mostly sits and moves rather than fills, took 3 to 4 times longer than the
same volume of balance unlocks through the identical mechanism, purely from running one at a
time. Not from any per-cancel cost, which is small. A cancel writes no trade and no fill-log row.

Grouping happens per wave, not once for the whole batch. A user touched by both a trade and a
cancel in one batch therefore pays two transactions on that row instead of one. That is a small
price for not making every balance write queue behind every fill, and it adds no contention,
since the two waves run one after the other anyway.

## Redelivery safety

Kafka delivers at least once, so the same event can arrive twice. Every effect a trade implies
has its own independent "have I already done this" check, rather than one shared gate:

- A trade already recorded is skipped. `spot_trades` has its own sequence uniqueness.
- An order that already counted a specific trade, per `spot_order_fill_log`, does not count it
  again.
- A wallet movement already applied, per the `wallet_ledger_entries` idempotency key, does
  nothing.

Each check is independent on purpose. If one effect succeeded but something else in the same
batch failed, a redelivery must re-check and re-apply only the parts that did not happen. It must
never skip them because a sibling effect already landed.

## Failure handling

| Situation | What happens |
|---|---|
| A malformed record | Logged and skipped. The rest of the batch proceeds normally. |
| One group hits a transient database lock conflict | Retried in place a few times with a short backoff. No other group is affected. |
| Anything else fails, such as a real bug or a lock conflict that outlasts its retries | The whole batch is left unacknowledged and Kafka redelivers it later. That is safe, because every effect above does nothing the second time. |

## Configuration that matters

- `spring.kafka.consumer.properties.max.poll.records` is how many events can land in one batch,
  and therefore in one grouping wave. Bigger batches group more, but a failure anywhere in a
  bigger batch means redelivering more.
- `spring.datasource.hikari.maximum-pool-size` is the real ceiling on how many groups can write
  to the database at the same time, however many are dispatched.

See `apps/spot-settlement/src/main/resources/application.yml` for the current values and the
reason for each.
