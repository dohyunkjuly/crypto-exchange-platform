# spot-matching-engine

The order book. It decides every order's fate, and nothing else does: it is the only thing that
reads `exchange.spot.commands.v1` and the only thing that writes `exchange.spot.events.v1`.
No HTTP beyond a health probe on :8090, no database, no clock: every event is derived from the
command that caused it, so replaying the log reproduces the book exactly.

- **In:** `commands.v1`, one thread per partition, a market's commands always on the same
  partition ([command-partition.md](../../../docs/reference/command-partition.md)).
- **Out:** `events.v1`, the single source of truth every other app is built from, and
  periodic book snapshots to object storage so a restart replays a bounded tail.
- **Holds:** the order book and the trigger book for parked conditional orders, in memory.

How it starts, recovers, snapshots and amends, and what happens when Kafka or storage is away:
[matching-engine.md](../../../docs/reference/matching-engine.md). Measured throughput and where the ceiling comes
from: [performance.md](../../../docs/reference/performance.md).
