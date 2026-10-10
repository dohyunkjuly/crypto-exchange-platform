# Performance

What the exchange does under load, in the numbers a person deciding whether to run it asks
for first. Every figure comes from `./gradlew perf`, which runs the benchmarks against the
stack on a laptop and writes [performance-report.md](performance-report.md); that page is the
latest run, this one is what the numbers mean, and the last section is why they come out as
they do.

## The six numbers

| | What it is | Why it matters |
|---|---|---|
| **Orders per second, one partition** | commands one market partition handles a second, end to end over Kafka | the unit of capacity: one engine instance, one thread; the exchange scales by spreading partitions over more instances |
| **Order to fill, through the website** | from `POST /bff/orders` to the private socket saying FILLED, p50 and p99 | what a trader feels on the trade screen |
| **Place an order over the API** | a signed `POST /v1/orders` answered 202, p50 and p99, ten keys at once | what a bot author feels |
| **Market sockets served at once** | sockets on one web-bff all receiving a fresh trade within ten seconds | how many people can watch a market from one instance |
| **Memory per resting order** | heap per order on a book of 2,000,000 | how big a book one engine holds |
| **Back after a crash** | engine killed with 30,000 unsnapshotted commands; seconds until it answers | how long trading stops |

The report shows each on a **single broker** -- the laptop default, the ceiling -- and, when
the run was made on the three-broker overlay, on **three brokers with every topic replicated
and `acks=all`**, which is the shape a deployment runs and the column to quote.

## How it scales, and where it stops

- **Throughput scales by instance.** A market lives on one partition, and an engine instance
  runs all its partitions on one thread; spreading partitions over more instances adds
  threads ([command-partition.md](command-partition.md)). The figure is per instance, and it
  is the one to multiply.
- **The ceiling is the Kafka round trip, not matching.** Matching takes microseconds; the
  engine publishes every command's events and waits for the broker before the next command.
  Three brokers with `acks=all` wait for a second copy, so that column is lower on purpose.
- **Settlement is bounded by MySQL.** Fills become rows in batches; the report's settlement
  section says how many a second and how far the balance trails the book.
- **Sockets scale horizontally.** Every web-bff instance is a full fan-out of the same events;
  nginx spreads connections across them.
- **What does not scale here**: one MySQL, no multi-region, a market's partition fixed once it
  has traded. [scalability.md](scalability.md) has the measured ceilings and the levers.

## Running it yourself

```bash
docker compose --profile full up -d                   # the stack, single broker
PERF_BFF_RATE_LIMIT=100000 docker compose --profile full up -d web-bff   # the harness is one address
cd backend && ./gradlew perf                           # 20-30 minutes; writes docs/reference/performance-report.md
```

For the second column, bring the stack up on three brokers and say so:

```bash
docker compose --profile full down -v                 # a single-broker log cannot join a cluster
docker compose -f docker-compose.yml -f docker-compose.perf.yml --profile full up -d
PERF_TOPOLOGY=cluster ./gradlew perf                  # perfCluster runs only on this topology
```

The set comes in three chunks, by what each needs running, and every chunk -- or a single
benchmark -- runs alone and rewrites only its own rows:

| Chunk | Benchmarks, each also a task of its own | Needs |
|---|---|---|
| `./gradlew perfStack` | `perfApi`, `perfOrderToFill`, `perfSettlement`, `perfSockets` | the full stack |
| `./gradlew perfEngine` | `perfThroughput`, `perfStops`, `perfSnapshots`, `perfRecovery`, `perfMemory` | Kafka and MinIO; pauses the stack's engine and consumers while it runs |
| `./gradlew perfCluster` | broker loss | `docker-compose.perf.yml` and `PERF_TOPOLOGY=cluster` |

`./gradlew tasks --group performance` lists them with what each measures.

Three things to know before trusting a number:

1. **It is one laptop.** The report names the machine. Run-to-run spread of the ceiling is
   measured and printed; read the rest with that much noise in mind.
2. **The local stack lifts the API's rate limits for the bots**, so the "limit trips where
   advertised" row is skipped unless the advertised limit is one a run can reach.
3. **The engine chunk fills Kafka.** Commands and events are kept forever; a run adds hundreds of
   thousands of records, and the integration tests that read `events.v1` from the start can
   then time out. `docker compose stop kafka && docker compose rm -f kafka && docker compose up -d kafka`
   clears it (the log has no volume locally).

## What each benchmark does

| Benchmark | Chunk | Workload |
|---|---|---|
| Throughput | `perfEngine` | 100,000 resting orders three times (the ceiling and its spread), then 60,000 commands where every sixth fills five |
| Stops firing | `perfEngine` | twenty windows of 2,000 orders; every fifth fires 500 parked stops with one trade; then 5,000 stops in one command |
| Snapshot overhead | `perfEngine` | 200,000 orders at `snapshot-every` off, 10,000, 30,000 and 100,000 |
| Memory and snapshot build | `perfEngine` | 5,000 to 2,000,000 resting orders across 100 markets, in memory |
| Recovery | `perfEngine` | 30,000 commands, engine closed, a new one started on the same group; time to its first event |
| Order to fill | `perfStack` | 1,000 limit buys through web-bff into resting sells, timed to the private socket's FILLED |
| Public API | `perfStack` | 2,000 signed orders from 10 keys, 2,000 depth reads, the weight limit if reachable |
| Settlement | `perfStack` | one order filling 2,000 of 2,200 resting orders; rows a second and `created_at - executed_at` per trade |
| Sockets | `perfStack` | market sockets opened in steps to 2,000, each step verified by a trade every socket must see |
| Broker loss | `perfCluster` | 300 commands a second for 60 s; a broker killed at 20 s and restarted at 40 s; longest silence and anything lost |

Every benchmark lives in `backend/perf/` (`engine/` for the ones that boot a real engine
in-process; the rest meet the running stack). Each ends in one `Report` line; the renderer in
the same module turns the lines into the page. Nothing is copied by hand.

## Why the numbers come out as they do

**The ceiling is the Kafka round trip.** `CommandConsumer.onCommand` blocks on `publishAll`
before it looks at the next record: one thread consumes, matches, publishes and waits for the
ack, once per command. Reading alone manages about 49,000 records a second, ten times what the
engine uses; one blocking producer round trip costs 0.25 to 0.35 ms on a single local broker,
which implies 3,000 to 4,000 commands a second; the report measures about 4,200. Matching itself
takes microseconds. `publishAll` issues every send before waiting on any, so a command that
emits five fills costs one wait, not five, and the crossing workload is not slower than the
no-fill ceiling. Three brokers with `acks=all` and `min.insync.replicas=2` lengthen the wait --
the leader must hear from a follower -- which is the price of a broker dying without a lost
order, and why the report's second column is lower.

**Stop cascades are matching-thread time nothing else on the partition can interleave with.**
The report shows the slowest ordinary window of a run in which every fifth window fires 500
stops, against the median; five thousand stops in one command is the size at which a pause is
unmistakably felt, and the report times it.
`MatchingEngine.MAX_CASCADE_ROUNDS = 8` caps how far a cascade compounds, not how many orders
fire in one round, and that is what drives the cost.

**`snapshot-every` is 30,000 because the curve flattens there.** The overhead is building the
`BookSnapshot` on the matching thread; the upload and the pointer to Kafka run on their own
thread ([matching-engine.md](matching-engine.md)). Past 30,000 the differences sit inside the
run-to-run noise, and larger values only lengthen the replay a restart pays. The sweep is a
worst case -- every command rests, nothing cancels, so every snapshot is of a deeper book --
and a real book, with market makers cancelling and replacing, stays far shallower.

**Snapshot build is linear to about 200,000 resting orders and then is not.** Ten times the
orders from 200,000 to 2,000,000 costs about twenty times the time: a hundred protobuf builders
each growing arrays, and GC pressure from millions of short-lived objects. The 2,000,000 case
needs a 3 GB heap to complete at all, which is the signal: 20,000 orders in each of 100 markets
on one partition is an extreme book, and the lever is more partitions. Staggering snapshots per
market was considered and rejected: every snapshot would have to resume from the oldest
included offset, trading a smaller pause for a longer worst-case recovery.

**Memory is per order, not per market.** The heap per resting order falls from about 680 bytes
at 5,000 orders to about 530 at 2,000,000 as fixed costs spread out; there is no per-market
cost large enough to show. Measured across `System.gc()` settling passes (advisory, so
approximate).

**Recovery replays the tail.** A restart restores the newest snapshot for its partitions and
replays the commands after it, so the pause is bounded by `snapshot-every` and
`snapshot-max-interval-ms`; the report's figure is the worst case within one interval.

**Traps.** Each fresh engine restores whatever snapshot exists for its partitions, including
ones left by other runs on the same eight partitions: the first overhead sweep inherited
ever-larger leftover books and showed *negative* overhead, which is why the benchmarks use
random consumer groups and a Kafka reset before a run is the safe habit. Whichever
configuration runs first in a cold JVM measures slower, which is why every comparison runs a
discarded warm-up. And the same ceiling measured on separate days gave 4,483 and 3,317 a
second, a 25% spread from a local broker, the JIT and whatever else the machine was doing --
which is why the report measures the spread each run and prints it beside the rest.
