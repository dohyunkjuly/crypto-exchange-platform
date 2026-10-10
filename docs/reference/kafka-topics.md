# Kafka topics

| Topic | Carries | Retention |
|---|---|---|
| `exchange.spot.commands.v1` | the API's requests to the engine | forever |
| `exchange.spot.events.v1` | everything the engine did: the single source of truth | forever |
| `exchange.spot.snapshot-pointers.v1` | which object holds each partition's latest snapshot | compacted |
| `exchange.spot.settlements.v1` | fills and balance changes, for the private sockets | finite, **may drop** |
| `exchange.spot.depth.v1` | each level's new size, for `depthUpdate` | one hour, **may drop** |
| `exchange.spot.engine-status.v1` | the engine's heartbeat, one per live partition a second | one minute, **may drop** |

- **Commands and events are kept forever** (`retention.ms=-1`). Recovery replays the command
  log ([matching-engine.md](matching-engine.md)), and nothing can replay a topic that dropped a
  trade. Before this was set, the broker's 7-day default would have cut older orders out of a
  rebuild silently.
- **The two finite topics feed live pushes** a client recovers over REST after a reconnect
  ([sockets.md](sockets.md)). A frame nobody is listening for is worth nothing.
- **The heartbeat is a presence signal**, not a record ([trading-gate.md](trading-gate.md)).
  Partition N of it carries command partition N; a reader asks only "did one arrive lately",
  so losing it loses nothing.
- **Partitioning.** A market's commands go to `spot_symbols.command_partition`, not to a hash
  of the symbol ([command-partition.md](command-partition.md)). The events topic is keyed by
  symbol, and `(symbol, sequence)` is unique, dense and monotonic: total order, gap detection
  and idempotency in one number.
- **Consumer groups.** If every instance must receive a message (the socket fan-outs), each
  instance has its own group, prefixed with the app's name. If the work is shared (settlement,
  market-data, spot-copy), one group. The apps create their own topics (`*TopicConfig`), with
  `modify-topic-configs: true` so a topic that exists with the wrong config is fixed too.
- `settlements.v1` and `events.v1` are consumed at least once. Every effect has its own
  independent "have I done this" check ([settlement-pipeline.md](settlement-pipeline.md)). The
  whole path, hop by hop, and what is not covered: [delivery-guarantees.md](delivery-guarantees.md).
