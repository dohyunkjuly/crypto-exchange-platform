# Delivery guarantees

What happens to an order when a process dies or a message arrives twice, hop by hop. Kafka
delivers at least once everywhere in this system; there is no exactly-once delivery anywhere.
What the system guarantees instead is that every **effect** -- a trade row, a balance
movement, a candle's volume -- happens once, because each hop has its own way of recognising
work it has already done.

Is that a guarantee? For everything below the line in "Known gaps", yes, and each one has a
test that breaks when its guard is removed. The gaps are real and are listed, with what would
close them.

## The path

```
API ──commands.v1──▶ engine ──events.v1──▶ settlement ──settlements.v1──▶ sockets
                                   └──────▶ market-data ──depth.v1──▶ sockets
```

| Hop | Producer | Consumer | Duplicates are caught by |
|---|---|---|---|
| API → `commands.v1` | outbox row in the order's transaction; 202 on commit; published off the request thread; `acks=all`, `enable.idempotence` | engine: static partitions, manual ack | the engine's `command_id` memory |
| engine → `events.v1` | `acks=all`, `enable.idempotence`, retried in-process, then exit | settlement: batch, manual ack | `(symbol, sequence)`: trades, fill log, ledger key |
| engine → `events.v1` | same | market-data: batch, manual ack | `(symbol, sequence)` watermark per candle, book and tape |
| settlement → `settlements.v1` | `acks=1`, 5 s to deliver, then **dropped** | every socket instance, own group | nothing; the topic may drop, REST is the record |
| market-data → `depth.v1` | `acks=1`, **dropped** | every socket instance, own group | nothing; same |

## API to engine

A command is never published from inside the transaction that creates it. `placeOrder` locks
funds, saves the row and writes the command to `spot_command_outbox` -- three database
writes, one commit. `amendOrder` does the same with its claim and its extra lock.

**The 202 goes out as soon as that commit has happened.** The row is the promise: from then
the command reaches the engine whatever becomes of this process or of Kafka. The request does
not wait for the broker, because waiting cost a thread per order for the length of a Kafka
round trip -- a hundred orders at once was a hundred threads doing nothing else.

The row is handed to `CommandOutboxPublisher`, which sends it within milliseconds: a lane per
command partition, each sending a batch, waiting for every acknowledgement, stamping
`published_at` on the acknowledged rows in one update, and only then taking the next batch.
Whatever is not acknowledged is left unstamped, and `CommandOutboxSweeper` -- on every API
instance, every second -- sends whatever is still unstamped after six seconds, oldest first.
That covers a broker outage, a full lane, and everything in memory when a process dies.

The hand-off happens once the transaction has **ended**, not merely committed, and the
difference took the API down once. An `afterCommit` hook runs while the transaction still holds
its database connection, and anything there that needs a connection of its own takes a second:
ten requests each holding one and waiting for another is the whole pool (Hikari's default is
ten) held by requests that can never finish, until it gives up thirty seconds later. One order
at a time never shows it; ten at once always does. So `OrderService` runs each of these as a
`TransactionTemplate` that returns, *then* hands off.
`OrderLifecycleIT.aBurstLargerThanTheConnectionPoolIsAllAccepted` is the test.

This is what closed the phantom order: a transaction cannot un-send a Kafka record, and a
publish that timed out inside the transaction rolled the row and the lock back while the
producer's own retries landed the command anyway. Now the database has the order before Kafka
can, always. The failure it leaves instead is an order sitting in `NEW` with funds locked and
no engine knowledge of it, until the sweeper gets through. Visible (`published_at IS NULL`),
bounded, and while it lasts the funds are held for an order the user was told was accepted.

**A command never overtakes an older one for the same order.** A client holding a 202 can
cancel at once, while the place is still on its way out, and the engine answers a cancel for an
order it has never seen with silence -- leaving the order resting with its owner believing it
gone. Two rules prevent it. A batch is cut where an order appears a second time, so a place
and its cancel are never in flight together. And a row that had an older sibling unstamped
when it was written is sent only if that sibling has been stamped since; if not -- it failed,
or another API instance has it -- both are left to the sweeper, which sends in `created_at`
order. Because a lane settles a batch before starting the next, that is one read, never a
wait. A cancel-all names no order, so it is ordered against everything of its user.
`OrderLifecycleIT.aCancelNeverOvertakesItsPlace` and `CommandOutboxPublisherTest` are the tests.

A cancel with nothing of its order pending writes nothing, so it has no row to fall back on:
it publishes directly and answers after the broker has it, as before. Two users' orders on one
market may swap places during a fault, costing one of them time priority; the fault already
cost more.

**A dead broker no longer shows in response times.** Clients keep getting fast 202s while
orders pile up unsent with their funds locked. The signal is the log: the publisher warns on
every command it could not get acknowledged, and the sweeper reports how many overdue commands
it found on each pass. Alert on those.

The producer is idempotent, so its own retries never write the same record twice. What
reaches the engine twice is a re-send by application code: a row the publisher sent but could
not stamp, then the sweeper sends again. The engine catches that: each market remembers the ids
of its last 10,000 commands (`SymbolState.MAX_REMEMBERED_COMMANDS`), a repeat produces no
events, and the set is in the snapshot and rebuilt by replay, so a restart forgets nothing
([matching-engine.md](matching-engine.md), "Recovery and replay"). The outbox row *is* the
command -- same bytes, same `command_id` -- which is what makes a re-send a repeat rather
than a second order.

This matters more than it looks. Settlement dedupes by sequence number, and a command the
engine matched *twice* would get *new* sequence numbers -- to settlement they are new trades,
and it moves the money again. The resting-order id guard in `OrderBook` catches a repeat only
while the order is still on the book; a market, IOC or FOK order, or a limit that filled
completely, has nothing there to catch it. The command id is the only guard for those.

| Property | Default | Meaning |
|---|---|---|
| `exchange.spot.outbox.lanes` | 8 | publisher threads; a partition always uses the same one |
| `exchange.spot.outbox.batch-max` | 500 | commands a lane sends before waiting for their acknowledgements |
| `exchange.spot.outbox.lane-capacity` | 50000 | commands a lane holds; past it they are left to the sweeper |
| `exchange.spot.outbox.sweep-ms` | 1000 | how often each instance sweeps |
| `exchange.spot.outbox.min-age-ms` | 6000 | younger rows are still the publisher's; must exceed its 5 s acknowledgement wait |
| `exchange.spot.outbox.batch` | 500 | rows per sweep |
| `exchange.spot.outbox.keep-published-ms` | 3600000 | published rows are deleted after this |

## Engine to settlement and market-data

The engine processes a command, publishes its events, then commits the offset -- in that
order, always ([`CommandConsumer`](../../backend/apps/spot-matching-engine/src/main/java/com/exchange/spot/matching/kafka/CommandConsumer.java)).
Commit first and a crash between the two loses trades that moved the book. Publish first and a
crash re-runs one command: the one at the committed offset, published but not yet committed.

That re-run is safe because the engine is a pure function of the log. The sequence counter is
restored from the snapshot and stepped identically through the replay, so the re-published
events carry the **same** `(symbol, sequence)` as the originals. Every consumer keys on it:

- **Settlement** -- `spot_trades` is unique on it; `spot_order_fill_log` records which trades an
  order has counted; `wallet_ledger_entries.idempotency_key` is unique and derived from the
  trade id, which is itself derived from the sequence. Three independent checks, on purpose,
  so a batch that half-applied re-applies only the half that did not
  ([settlement-pipeline.md](settlement-pipeline.md), "Redelivery safety").
- **Market-data** -- candles, the depth book and the trade tape each carry a `lastSequence`
  watermark and apply nothing at or below it.

A publish that fails is retried in-process with the *same* computed events, never by re-running
the command against the live book; past a bounded number of attempts the process exits so a
supervisor restarts it into the safe replay path. `PublishRetryDoesNotReprocessIT` is the test.

Settlement acknowledges a batch only after every effect committed. Anything that fails leaves
the batch unacknowledged for redelivery. Two things are dropped rather than retried, because
retrying cannot fix them and a stuck partition stops every market on it: a market MySQL does
not know, and a trade with the same user on both sides.

## Settlement and market-data to the sockets

`settlements.v1` and `depth.v1` are the only topics allowed to lose messages
([kafka-topics.md](kafka-topics.md)). Both producers use `acks=1` and drop on failure, and
the consumers never retry. This is deliberate: a frame a minute late is worse than no frame
([sockets.md](sockets.md)), and the record is always REST, which reads what settlement wrote.

## Known gaps

**An event for an order with no row pins settlement's partition.** `OrderEffectService.
requireOrder` throws, and `applyFillAtomically` returning zero rows throws, so the batch is
never acknowledged and is redelivered forever -- every market on that partition stops
settling. The outbox means the API can no longer produce such an order, but the invariant is
held by code paths, not the database: a database restored from a backup older than Kafka's
log, an operator replaying old commands, or a future bug would reach it. Treating a missing
row as a poison pill after a few redeliveries -- logged, skipped, the partition carried on,
as unknown symbols and self-trades already are -- would turn that into one order needing
repair. Until then, an alert on a batch redelivered more than a few times is the signal.

**The command-id memory is a window, not a history.** Ten thousand of the market's own
commands: minutes on a typical market, seconds at the partition ceiling. A repeat arriving
later is processed. That is the right trade against heap (100 bytes an entry, in every
snapshot), but it means an operator replaying an old slice of `commands.v1` into a live
engine is *not* protected and must not do it.

**An order placed while the engine is away is refused, not queued** -- for as long as the
engine stays away. The gate is a heartbeat read by the API ([trading-gate.md](trading-gate.md)),
and it has a window: an order accepted in the few seconds between the engine dying and its
partition going quiet, or published by the outbox after its lane filled, still waits in
`commands.v1` with its funds locked until an engine replays to it. That is the queued order
this design accepts, and it is seconds of orders, not hours.

**Two engine instances on one partition.** Partitions are assigned by config, not by Kafka, so
nothing stops two instances being given the same one. Both would process every command and
publish identical events; the sequence guards absorb that downstream, but every event is
produced and stored twice. A deployment rule, not a code guard
([matching-engine.md](matching-engine.md)).
