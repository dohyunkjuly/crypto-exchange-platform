# A market's command partition is fixed once it has traded

Each market's commands go to the partition in `spot_symbols.command_partition`, not to a hash
of the symbol. A hash cannot promise a busy market a partition of its own, and a market that
shares one waits behind the other markets' orders. The dev seed gives BTCUSDT partition 0 and
ETHUSDT partition 1 on their own, and spreads the other majors over 2 to 7.

An engine instance runs all of its partitions on one thread, so the isolation only pays off once
each hot partition has an instance of its own (`ENGINE_PARTITIONS=0`, `=1`, `=2,3,4,5,6,7`).
Partitions are how the work is divided; instances are what adds capacity.

**Never move a market that has traded.** The engine keeps a market's book and event sequence
under the partition its commands arrive on, and settlement skips a trade whose
`(symbol, sequence)` it has already seen. A moved market leaves its book stranded on the old
partition, and its new trades are skipped as duplicates. The entity never writes the column
(`updatable = false`); moving one would need a migration this repo does not have.

Pick the partition when listing the market, below `engine.partition-count`. The admin Markets
page offers only those, and shows which markets already share each. A partition past
the topic's last one is caught by `CommandPartitions` in every app that places orders: at
startup the app refuses to start and names the markets; a market listed while running refuses
orders with `MARKET_UNAVAILABLE` (5018) before any funds are locked.

The topic itself is declared by the engine (`CommandTopicConfig`, `engine.partition-count`,
kept forever), and by the `kafka-topics` one-shot in `docker-compose.yml` with the same shape,
so a stack without the engine -- CI, or `docker compose up -d` for the `*IT` tests -- has it
before anything produces. Left to auto-creation it would get one partition, and every app with
`CommandPartitions` would then refuse to start against the seeded markets.
