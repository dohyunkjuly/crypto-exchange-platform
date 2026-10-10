# spot-settlement

The only writer of order and wallet state from trading. It consumes `exchange.spot.events.v1`
and turns each batch into `spot_trades` rows, order fills, cancellations and the balance and
ledger movements they imply. Nothing else may touch `spot_orders` or `wallet_balances` because
of a trade, which is how a fill can never be settled twice.

- **In:** `events.v1`, at least once. Every effect carries its own idempotency check.
- **Out:** MySQL, and `exchange.spot.settlements.v1` for the private sockets' fill and balance
  frames (finite retention; a client reconciles over REST).
- No API of its own; :8091 is a health probe.
- Two nightly side jobs, off the settlement path: the fee sweep
  ([treasury.md](../../../docs/reference/treasury.md)) and the fee-tier recount
  ([fee-tiers.md](../../../docs/reference/fee-tiers.md)).

The batch pipeline and why it is ordered the way it is:
[settlement-pipeline.md](../../../docs/reference/settlement-pipeline.md). Every lock and release an order goes
through: [locked-funds.md](../../../docs/reference/locked-funds.md).
