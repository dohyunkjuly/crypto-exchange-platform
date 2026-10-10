# The matching engine

Everything here was checked against the running system, not guessed from the code. Every claim
was reproduced live by killing Kafka, killing MinIO and deleting topics.

## Interfaces

No HTTP, no database. Just two things:

- **Kafka.** `exchange.spot.commands.v1` in, `exchange.spot.events.v1` out. This is the only way
  to place an order or to hear about a trade. A heartbeat per live partition a second goes out
  on `exchange.spot.engine-status.v1`, which is how the API knows to pause a market whose
  engine is gone ([trading-gate.md](trading-gate.md)); it starts for a partition only once
  replay has reached the committed offset, and stops when the consumer stops polling.
- **Object storage.** MinIO locally, S3 in production. It holds periodic order book snapshots,
  so a restart does not have to replay the whole command log. It is optional. The engine is
  still correct without it, just slower to recover.

## Amending an order

`AmendOrder` looks in the trigger book first and the order book second, the same order a cancel
looks in. Every outcome is an event, including a refusal, because the API answered `202` before
the engine ran and locked funds that settlement can only return if told to.

- **In place.** A smaller total at the same price shrinks the order where it stands and keeps
  its queue position. `OrderAmended.priority_kept` says so.
- **Re-queued.** Anything else removes the order and sends a replacement, with the same id and
  its filled part intact, back through `OrderBook.submit`. A crossing amend and its self-trade
  prevention are therefore the same code a new order runs. `OrderAmended` comes first, then the
  trades, and there is no `OrderAccepted`: `OrderAmended.resting_quantity` already says what
  rests, and to a depth consumer a second event would be the same quantity arriving twice.
- **Refusals are decided before anything mutates**: the order is gone, the new total is not
  above what filled, a `LIMIT_MAKER` would cross, a new stop price already holds.
- Snapshots need nothing new. They store the book and the trigger book as they are.

## Startup

1. **Topics are reconciled.** `CommandTopicConfig`, `EventTopicConfig` and `SnapshotTopicConfig`
   each declare a `NewTopic` bean with its partition count, and infinite retention for the
   command topic. `spring.kafka.admin.modify-topic-configs: true` means this also fixes a topic
   that already exists with the wrong config, not only a missing one.
2. **The snapshot bucket is checked** (`SnapshotStore.ensureBucket`) and created if it is
   simply absent. The failure table below says what an unreachable bucket does.
3. **Each assigned partition is restored or replayed** (`CommandConsumer.onPartitionsAssigned`).
   If a snapshot exists, the engine loads the newest one and then replays commands from just
   past its offset. If not, it replays from the start of the log.

## When a dependency fails

"Startup" means the dependency is unreachable before the process has finished booting.
"Runtime" means it goes away while the engine is already up.

| Dependency | When | What happens |
|---|---|---|
| Kafka | Startup | **Still starts.** Topic setup retries for about 40 seconds, logs one `ERROR` and gives up. The app starts anyway and the consumer keeps retrying until Kafka appears. |
| Kafka | Runtime | **Reconnects on its own.** No restart needed. |
| Object storage | Startup | **Refuses to start.** It throws with the bucket, the endpoint and what to check. `engine.snapshot-storage.required=false` opts out on purpose. |
| Object storage | Runtime | **No effect on matching.** Snapshot writes run on their own background thread. A failed upload logs a `WARN` and retries at the next interval. |
| A malformed command | Runtime | That one record is logged and skipped. Everything else keeps flowing. |

The difference is deliberate. Kafka carries every order, so the engine waits for it as long as
it takes. Object storage is only an optimisation on a system that already survives its own
outages. Unreachable at boot looks like a config mistake worth refusing. Unreachable at runtime
looks like the passing blip it usually is.

## Snapshots

- One is taken after `engine.snapshot-every` commands **or** after
  `engine.snapshot-max-interval-ms`, whichever comes first, per partition. Counting alone would
  starve a quiet partition now that the command log never expires. Time alone would snapshot a
  busy partition far too often.
- The check runs on the matching thread itself, never on a background timer. `OrderBook` is not
  thread-safe, and a second thread reading it while this one changes it is exactly the race the
  design rules out.
- The bytes go to object storage. A pointer naming them goes on the compacted
  `snapshot-pointers.v1` topic. The object lands **before** the pointer is published. Otherwise
  the pointer could name something that does not exist yet.
- The snapshot a new one replaces is deleted once both are durable. Not by an age rule, which
  cannot tell "replaced" from "old, but still the only copy a quiet partition has".

## Recovery and replay

- `commands.v1` never expires (`retention.ms=-1`). Without a usable snapshot, recovery replays
  the whole log. That is correct, but before this was set the broker's default retention of
  7 days would have silently cut older orders out of the rebuild.
- A restart does not publish events again for commands below the committed offset. Replaying
  to rebuild state is not the same as announcing trades that already happened. The one
  command that can be published twice is the one in flight at the crash: published, but not
  yet committed. Settlement absorbs that by sequence number
  ([settlement-pipeline.md](settlement-pipeline.md), "Redelivery safety").
- A command the engine has already processed is skipped, not processed again. Each market
  remembers the ids of its last 10,000 commands (`SymbolState.MAX_REMEMBERED_COMMANDS`), and
  a repeat produces nothing -- the first copy produced the events. The set is in the
  snapshot, so a repeat that lands after a restart is still a repeat. This is what makes a
  re-sent command safe: settlement dedupes by sequence, and a command matched a second time
  would get *new* sequence numbers and settle as new trades. The producer's
  `enable.idempotence` stops most repeats before they are written; this catches the ones it
  cannot, such as a relay re-sending after a crash.
- `events.v1` keeps whatever retention the broker defaults to. This is left undecided on
  purpose. Nothing downstream needs it kept, and the engine's own recovery does not use it.

## Which partition a market uses

`spot_symbols.command_partition`, fixed once the market has traded:
[command-partition.md](command-partition.md).

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `engine.partitions` | `0,1,2,3,4,5,6,7` | Which command partitions this instance owns. |
| `engine.partition-count` | `8` | The total partition count. It fixes the pointer topic's size, whatever this instance owns. |
| `engine.snapshot-every` | `30000` | Commands between snapshots, per partition. |
| `engine.snapshot-max-interval-ms` | `600000` (10 min) | The longest a partition may go without a snapshot, however quiet. |
| `engine.snapshot-storage.required` | `true` | Whether an unreachable bucket blocks startup. |
| `SNAPSHOT_BUCKET` / `_REGION` | local MinIO defaults | Where snapshots go. See `backend/.env.example`. |
| `SNAPSHOT_S3_ENDPOINT` | `http://localhost:9000` | **Leave unset in production.** Blank means real S3, not MinIO. |
| `SNAPSHOT_S3_ACCESS_KEY` / `_SECRET_KEY` | MinIO dev credentials | **Leave unset in production.** Blank means the instance's IAM role. |

## Try it yourself

```bash
# Kafka down at startup: still starts, about 40s delay, then retries forever
docker stop exchange-kafka
./gradlew :apps:spot-matching-engine:bootRun   # watch it start anyway
docker start exchange-kafka                    # watch it reconnect on its own

# Object storage down at startup: refuses to start
./gradlew :apps:spot-matching-engine:bootRun --args="--engine.snapshot-storage.endpoint=http://localhost:9999"

# The same, but opted out on purpose: starts, warns, saves nothing
./gradlew :apps:spot-matching-engine:bootRun --args="--engine.snapshot-storage.endpoint=http://localhost:9999 --engine.snapshot-storage.required=false"

# A topic recreated from nothing gets this repo's config, not the broker's default
docker exec exchange-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic exchange.spot.events.v1
./gradlew :apps:spot-matching-engine:bootRun   # recreated with the right partition count
```
