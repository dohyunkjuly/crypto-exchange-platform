# How it scales, and where the ceilings are

What the system does today, with the numbers that were measured (the latest run is
[performance-report.md](performance-report.md); what they mean, [performance.md](performance.md)),
and the limits that come with each choice. Nothing here is a plan.

## The shape

```
web / bots ─▶ web-bff, spot-open-api ─▶ commands.v1 ─▶ matching-engine ─▶ events.v1 ─┬─▶ spot-settlement ─▶ MySQL
   (stateless, N instances)            (per market     (1 thread per                  ├─▶ market-data ─▶ MongoDB
                                        partition)      instance)                     └─▶ the socket fan-outs
```

One write path, in one direction. The engine is the only thing that decides an order's fate,
settlement is the only thing that writes balances and order rows, and everything a client reads
live is a projection built off the event stream.

## The engine: scale by instance, divide by partition

- An engine instance runs all of its partitions on one thread, no locks, no shared state
  between partitions ([matching-engine.md](matching-engine.md)). A partition is processed by
  exactly one thread, so it is the unit of ordering; the instance is the unit of capacity.
- A market lives on a fixed partition ([command-partition.md](command-partition.md)), and an
  instance can own any subset of partitions (`ENGINE_PARTITIONS`). Capacity grows by spreading
  partitions over more instances: a hot market gets a partition of its own, and that partition
  an instance of its own. More partitions on the same instance add nothing.
- **Measured ceiling: about 4,200 commands per second per engine instance**, end to end over Kafka,
  whether commands cross or not. The bound is the publish-and-ack round trip per command, not
  matching: reading alone manages about 49,000 per second, and matching is microseconds.
  Raising the ceiling per instance would mean pipelining publishes across commands, which
  trades away the property that a command is fully published before the next is read.
- A book of two million resting orders costs about 1 GB of heap, about 530 bytes per order ([performance-report.md](performance-report.md)).
- **Recovery is bounded, not proportional to history.** A snapshot every 30,000 commands or 10
  minutes per partition means a restart replays at most that tail -- about 11 seconds for a
  full 30,000. Snapshotting at that setting costs nothing measurable, and building one takes
  milliseconds for a normal book and about a third of a second for two million orders.

## Settlement: one writer, parallel inside

- One writer for order and wallet state means no cross-service write contention and no
  double-settled fill, at the cost that every balance change queues through this app.
- Inside a batch, work is grouped by row (an order, a `(user, asset)` balance) and the groups
  run in parallel ([settlement-pipeline.md](settlement-pipeline.md)). A large order matched
  against many counterparties pays one write, not one per trade. Real concurrency is the
  database connection pool.
- **Measured: about 2,500 fills a second**, with balances committed about 0.4 seconds after
  the engine executed the fill.
- **The ceiling is MySQL.** Settlement throughput is bounded by row writes per second on
  `wallet_balances` and `spot_orders`, and by the size of `max.poll.records`. There is no
  sharding of the database; `user`, `wallet` and `spot` keep no foreign keys across each other
  ([migrations.md](migrations.md)) so that each could move to its own database, but none has.

## Reads off the write path

- Candles, tickers, depth and the trade tape are built by market-data from `events.v1` into
  MongoDB ([depth-fold.md](depth-fold.md)). Chart and book traffic never touches MySQL, and the
  whole projection can be rebuilt by replaying the topic.
- The two API apps hold no state. Sessions and keys are rows in MySQL, rate limits are counters
  in Redis, and every instance of both apps consumes every event for its sockets
  ([sockets.md](sockets.md)). Add instances behind a load balancer; a socket lives on one
  instance and reconnects within 24 hours, so a rolling deploy never strands one.
- Frames are whole state, so a slow client or a dropped frame costs nothing to recover
  ([sockets.md](sockets.md)).

## Honest limits

- One MySQL, with no replicas the code knows about, and no multi-region story. Kafka's
  replication is a setting (`KAFKA_REPLICATION_FACTOR`, three in a deployment); the laptop runs
  one broker ([deployment.md](deployment.md)).
- A market cannot move partitions once it has traded. Rebalancing hot markets is a migration
  this repo does not have.
- The 4,200 per second ceiling is per engine instance, and a single market cannot be
  split across partitions or instances. A market busier than that needs the publish path
  changed.
- Settlement is a single consumer group over a topic partitioned by symbol, so its parallelism
  across markets is the partition count, and within a market it is the batch grouping.
- The copier is another single group over the same events, and each copy is one more order
  through the order path: a lead order with N followers costs N placements, so a leader's
  follower cap (`copy_settings`) is also the copier's load cap
  ([copy-trading.md](copy-trading.md)).
- Run-to-run variance is measured on every run and printed in the report; it has ranged from
  under 5% to 25% on one laptop. Treat the numbers as directional and re-measure under the
  target conditions before sizing anything.
