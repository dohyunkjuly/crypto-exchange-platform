# How the public depth is built

The engine never emits a rendered book. market-data builds the depth by folding
`exchange.spot.events.v1` into an in-memory ladder per symbol (`DepthWriter`) and writing the
changed levels to MongoDB. Every consumer of depth, REST and both sockets, reads that.

## Only what was on the book can leave it

Each event says how much rested, and only that moves a level:

| Event | Level change |
|---|---|
| `OrderAccepted` | + `resting_quantity` at `price`. Not `quantity`: an order that crossed on arrival rests only its remainder |
| `TradeExecuted` | − `quantity` at `price`, on the **maker's** side. The taker was never on the book |
| `OrderCanceled` | − `canceled_quantity` at `price`, only when `rested` is true |
| `OrderAmended` | − `old_remaining_quantity` at `old_price`, + `resting_quantity` at `new_price`; nothing for a parked order |

`rested` exists because a price on a cancel is not enough: an IOC remainder, or a taker cut
short by self-trade prevention, carries a price and never rested. Subtracting it would take
other users' quantity off a level the order was never on. Its absence is not enough either: a
quote-sized market order's cancel has no price and reports its quantity in the quote asset.
The engine is the only one who knows which is which, so the flag is on the event.

## One event, two levels

An amend to a new price leaves one level and joins another under one event sequence. The
writer dedupes redeliveries by sequence, so a second leg with the same sequence looked like a
redelivery until `DepthWriter` learned that one event can have two legs
(`foldingSequence`). A real redelivery still applies neither, because the first leg is skipped.

## The write and the feed

- Levels that changed since the last successful write are tracked separately from the batch.
  When a write fails the batch is not acknowledged, but the in-memory book has already moved
  on, so on redelivery every delta is skipped by the watermark. Keying the write off "did
  anything apply" would then write nothing, and the pending levels would be lost silently.
- After the book holding a level is written, never before, the level's new absolute quantity
  goes to `exchange.spot.depth.v1` with the event sequence (`LevelChangeSink`). A change on
  the feed that the store lacks would let a client resync from a snapshot behind it.
  spot-open-api folds these into one `depthUpdate` frame per symbol every 100 ms.
- The sequence is the engine's own event sequence per market, the same number the REST body
  and the `depth` frames carry, so all three agree by construction.

## Two reads, one rule

REST returns the stored ladder, up to 5,000 levels a side: the whole book for any market we
run, stamped with `lastSequence`. The `depth` socket channel carries the top 50 a side, whole,
on every change, with the same stamp. A REST body whose `lastSequence` is behind the last frame
is stale and is dropped.
