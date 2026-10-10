# market-data

Builds what a chart or an order book screen reads: candles, tickers, the public depth and the
trade tape, folded from `exchange.spot.events.v1` into MongoDB. Everything here is a
projection; drop the collections and replay the topic, since `spot_trades` in MySQL remains
the record of what happened.

A separate app rather than part of settlement because of failure domains: a MongoDB hiccup must
not stop balances from settling. **Run exactly one instance**; the depth writer holds each book
in memory and a second writer is made to fail loudly. No API; :8092 is a health probe.

- **In:** `events.v1`.
- **Out:** MongoDB, read by both API apps, and `exchange.spot.depth.v1` with each level's new
  size for the full-ladder `depthUpdate` channel.

How the depth is folded and why only what rested can leave it:
[depth-fold.md](../../../docs/reference/depth-fold.md).
