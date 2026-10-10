# spot-copy

Copy trading. When a lead account's order closes with something filled, each follower's copy
account gets the same trade, scaled to the follower's money. It reads
`exchange.spot.events.v1` and places the copies through the same order path web-bff uses, so
fees, limits, the trading gate and locked funds apply as to any order.

It writes no order and no balance itself: settlement stays the only writer of both. A copy is
placed once, because its row in `copy_orders` commits in the same transaction as the order.

- **In:** `events.v1`, at least once, under its own group. Everything it does is safe to see
  twice.
- **Out:** commands, through `OrderService` and its outbox, and the `copy_*` tables.
- No API; :8093 is a health probe.

The design, the sizing rules and what happens when a copy fails:
[copy-trading.md](../../../docs/reference/copy-trading.md).
