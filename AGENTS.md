# Rules

Spot crypto exchange. `backend/` is a Spring Boot monorepo, `web/` is Next.js 16. How to run it,
and the map: [CONTRIBUTING.md](CONTRIBUTING.md). What it is, for an outsider: [README.md](README.md).

## Principles

- **Reuse before you write it again.** Search first. Extend or generalise what exists rather
  than building a parallel version.
- **Check the existing style before you implement.** Match the file next to the one you are
  editing. The few style rules whose reason is not visible in the code are in
  [docs/STYLE.md](docs/STYLE.md).
- **Comments are short and say why**, never what. A paragraph is a design decision: write it
  once on the class, and point to a reference doc instead of repeating it.
- **Tests prove what they claim.** Cover the failure cases, and break the thing a test guards
  to see it fail.
- **Verify before you say it works:** `cd backend && ./gradlew build`, then
  `cd web && npm test && npm run build`, and the same in `admin/`. `*IT` tests need
  `docker compose up -d`.
- **Changed spot-open-api?** A new or changed endpoint, field, channel, error code or limit is
  edited in `api-docs/content/includes/` in the same commit. Nothing checks this for you.

## Considerations

Each of these breaks silently or cannot be undone. Read the linked page before touching the
area.

- **Money is never a float.** `BIGINT` × 1e8, `long`, decimal strings on the wire.
  [docs/reference/money.md](docs/reference/money.md)
- **No secret or config value has a default**; a missing one crashes the app at startup. How
  each credential is stored, and why sessions are opaque tokens, not JWT.
  [docs/reference/credentials.md](docs/reference/credentials.md)
- **Never edit a migration that has run anywhere but your machine. Never rename or remove a
  stored enum value. Defaults in Java, not SQL. No foreign keys across domains.**
  [docs/reference/migrations.md](docs/reference/migrations.md)
- **Never `@Data` on an entity**, and which rows carry `@Version`.
  [docs/reference/entities.md](docs/reference/entities.md)
- **Error codes belong to each app and are never renumbered**; a shared service owns none.
  [docs/reference/error-codes.md](docs/reference/error-codes.md)
- **Commands and events are kept forever**; only `settlements.v1`, `depth.v1` and
  `engine-status.v1` may drop.
  [docs/reference/kafka-topics.md](docs/reference/kafka-topics.md)
- **Every hop delivers at least once; every effect happens once.** The API writes a command
  in the same transaction as its order and publishes after the commit, never inside it. The
  engine publishes before it commits and dedupes on `command_id`; everything downstream
  dedupes on `(symbol, sequence)`. A command matched twice would mint new sequences, which
  nothing catches. [docs/reference/delivery-guarantees.md](docs/reference/delivery-guarantees.md)
- **The trading gate sits at each app's edge**, spot-open-api must call it from its HMAC
  filter, and cancelling and reading are never gated. **A market whose engine has gone quiet is
  paused** by `OrderService` on the engine's heartbeat; an app with an order path must consume it.
  [docs/reference/trading-gate.md](docs/reference/trading-gate.md)
- **A market's `command_partition` is fixed once it has traded.**
  [docs/reference/command-partition.md](docs/reference/command-partition.md)
- **Locked funds move in one direction at a time**: the API locks more before publishing,
  settlement releases after the engine confirms, one amend per order in flight.
  [docs/reference/locked-funds.md](docs/reference/locked-funds.md)
- **Only quantity that was on the book leaves the public depth**: `resting_quantity` on an
  accept, `rested` on a cancel. [docs/reference/depth-fold.md](docs/reference/depth-fold.md)
- **Frames are whole state, not diffs**, except `depthUpdate`. Both sockets are one shared
  fan-out. [docs/reference/sockets.md](docs/reference/sockets.md)
- **Next renders, it does not fetch. The browser talks to web-bff only, `credentials: "include"`
  on every request, never retry a mutation.**
  [docs/reference/frontend-session.md](docs/reference/frontend-session.md)
- **A sub-account is gated by its master and never logs in.** Its keys are minted on the web
  with the master's TOTP; transfers are one transaction, idempotent on the client's id.
  [docs/reference/sub-accounts.md](docs/reference/sub-accounts.md)
- **Staff never share a process, a cookie or a login with customers.** `admin_users` is its own
  table, every admin write is audited in the same transaction, and anything that moves money
  or a price needs a fresh authenticator code. [docs/reference/admin.md](docs/reference/admin.md)
- **A fee tier is the family's trailing 30 UTC days, in USDT, written on the master by a nightly
  job that recounts from `spot_trades`.** Nothing on the fill path writes volume; a quote with
  no USDT market counts for nobody. [docs/reference/fee-tiers.md](docs/reference/fee-tiers.md)
- **The exchange's own money is two `SYSTEM` accounts, and fees reach them by a sweep, not by
  settlement.** Revenue stays in native units and is never valued in admin; the page shows how
  far the sweep has got. [docs/reference/treasury.md](docs/reference/treasury.md)
- **Copy trading never values a holding to move money, and never trades for a follower by
  hand.** A copy is an ordinary order through `OrderService`; the share is on realized profit
  at the follow's frozen rate; the one market-price valuation is the leader's nightly figures.
  An app that places orders must scan `com.exchange.copy` or the hook is silently absent.
  [docs/reference/copy-trading.md](docs/reference/copy-trading.md)
- **The edge is one nginx, by hostname; every backend trusts `X-Forwarded-For` from private
  ranges only.** Rate limits, key IP allowlists and audit rows read the client address through
  it, so a new app that faces the proxy sets `forward-headers-strategy: native` too.
  [docs/reference/deployment.md](docs/reference/deployment.md)
- **White-label: nothing here touches a blockchain.** The deposit, withdrawal, address and
  allowlist entities have no services on purpose.
  [docs/reference/white-label.md](docs/reference/white-label.md)
- How the engine starts, recovers, snapshots and amends:
  [docs/reference/matching-engine.md](docs/reference/matching-engine.md). How a batch of events
  becomes rows and balances: [docs/reference/settlement-pipeline.md](docs/reference/settlement-pipeline.md).
  Every order type and its sizing rules: [docs/reference/order-types.md](docs/reference/order-types.md).
  The public API's signing filter and rate limits:
  [docs/reference/api-signing-and-limits.md](docs/reference/api-signing-and-limits.md).
  Measured throughput and where it scales: [docs/reference/scalability.md](docs/reference/scalability.md).
  What the performance numbers mean and how to measure them (`./gradlew perf`, in chunks):
  [docs/reference/performance.md](docs/reference/performance.md). The latest run,
  [docs/reference/performance-report.md](docs/reference/performance-report.md), is generated by
  the benchmarks; never edit it by hand.
