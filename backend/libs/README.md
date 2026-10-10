# libs

What the apps share. Dependencies go one way, `user ← wallet ← spot`, and a domain module never
depends on an app. Does more than one app need it? Then it lives here, throws plain exceptions
and owns no error code; each app maps them to its own ([error-codes.md](../../docs/reference/error-codes.md)).

| Module | Holds |
|---|---|
| `common/` | plain Java, no Spring: `Money`, ULIDs, the crypto primitives, Kafka topic names |
| `contracts/` | the protobuf commands, events and snapshots. Additive changes only |
| `core/` | the HTTP envelope, the shared exception handler, `RateLimiter` |
| `persistence/` | Flyway migrations and the JPA dependencies, one version sequence for every domain ([migrations.md](../../docs/reference/migrations.md)) |
| `domain/user` | identity, sessions, MFA, KYC, API keys, the trading gate |
| `domain/wallet` | assets, balances, the ledger; deposit and withdrawal entities with no services ([white-label.md](../../docs/reference/white-label.md)) |
| `domain/fee` | fee tiers and overrides |
| `domain/spot` | symbols, orders, trades, the order service both API apps use, the private socket consumers |
| `domain/marketdata` | the read side of candles, tickers and depth, and the market socket fan-out |
