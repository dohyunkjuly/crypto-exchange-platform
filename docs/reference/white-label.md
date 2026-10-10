# White-label: nothing here touches a blockchain

The operator brings their own custody. There is no chain watcher, no address derivation and no
withdrawal signing anywhere in this repo, and there never will be.

`wallet.deposit`, `wallet.withdrawal`, `wallet.address` and `wallet.allowlist` are entities
with no services and no endpoints. They exist so an integrator has tables to map their custody
onto. `can_withdraw` exists on an API key and no endpoint uses it. `/v1/wallet` is balances only.

Nothing in the source names an exchange. The operator's name, logos, header names, error
messages and the developer site carry no brand of ours, because they end up in every client
library written against a deployment, on every customer's phone, and in every search result.

## What the operator sets

Everything below is read from the environment, listed by the process that reads it. **Nothing
that identifies an operator or a secret has a default**: a missing one stops the app at startup
rather than shipping a placeholder ([credentials.md](credentials.md)). The three `.env.example`
files carry the reasoning beside each line; `docker-compose.yml` shows one working assignment.
A variable several apps read is listed under each, because each process is deployed on its own
and must be given it.

### web (Next.js, build time)

| Variable | What it is |
|---|---|
| `NEXT_PUBLIC_EXCHANGE_NAME` | the operator's name: tab title, footer, sign-in copy, logo alt text. No default |
| `NEXT_PUBLIC_BFF_URL` | where web-bff is for the browser; `/` behind nginx (same origin). No default |
| `NEXT_PUBLIC_GOOGLE_CLIENT_ID` | optional; blank hides Google sign-in |
| `web/public/brand/*.svg`, `web/src/app/icon.svg`, `apple-icon.png` | the logos and favicons ([README](../../web/public/brand/README.md)) |

### admin (Next.js, build time)

| Variable | What it is |
|---|---|
| `NEXT_PUBLIC_EXCHANGE_NAME` | the operator's name in the console's title and header. No default |
| `NEXT_PUBLIC_ADMIN_BFF_URL` | where admin-bff is for the browser; `/` behind nginx. No default |
| `admin/src/app/icon.svg`, `apple-icon.png` | the favicons |

### web-bff

| Variable | What it is |
|---|---|
| `EXCHANGE_NAME` | the TOTP issuer on every customer's phone. No default |
| `API_KEY_ENCRYPTION_KEY`, `MFA_ENCRYPTION_KEY` | AES-256 keys over API-key secrets and TOTP seeds, separate on purpose. No default |
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | MySQL |
| `FLYWAY_LOCATIONS` | `classpath:db/migration`; add `db/seed` only on a developer's machine |
| `KAFKA_BOOTSTRAP_SERVERS` | the outbox publisher and the event consumers behind the sockets |
| `KAFKA_REPLICATION_FACTOR` | copies of each topic this app creates; 1 on a laptop, 3 in a deployment ([deployment.md](deployment.md)) |
| `REDIS_HOST`, `REDIS_PORT` | the request rate limiter |
| `MONGODB_URI` | candles, depth and the tape served to the browser |
| `MAIL_MODE`, `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_SMTP_AUTH`, `MAIL_SMTP_STARTTLS`, `MAIL_FROM` | the relay for verification and reset codes; `smtp` in production, `log` prints codes to the log; `MAIL_FROM` must be a domain the relay may send for |
| `GOOGLE_CLIENT_ID` | optional; the `aud` an ID token must carry |
| `EXCHANGE_CORS_ALLOWED_ORIGIN` | the page's origin, checked on the socket handshake; `https://app.<domain>` |
| `SPRING_PROFILES_ACTIVE` | never `dev` in production: it enables the faucet and the seeded personas |

### admin-bff

| Variable | What it is |
|---|---|
| `EXCHANGE_NAME` | the TOTP issuer on every staff phone, as "<name> Admin". No default |
| `MFA_ENCRYPTION_KEY` | over staff TOTP seeds. No default |
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `FLYWAY_LOCATIONS` | MySQL, as above |
| `ADMIN_WEB_ORIGIN` | the console's origin; `https://admin.<domain>` |
| `ENGINE_PARTITION_COUNT` | the engine's partition count, so listing a market picks a partition that exists. No default |

### spot-open-api

| Variable | What it is |
|---|---|
| `API_KEY_ENCRYPTION_KEY`, `MFA_ENCRYPTION_KEY` | the signing gate decrypts key secrets; the user module it scans needs both. No default |
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `FLYWAY_LOCATIONS` | MySQL |
| `KAFKA_BOOTSTRAP_SERVERS` | the outbox publisher and the private-frame consumers |
| `KAFKA_REPLICATION_FACTOR` | copies of each topic this app creates; 1 on a laptop, 3 in a deployment ([deployment.md](deployment.md)) |
| `REDIS_HOST`, `REDIS_PORT` | weight, order-count and socket budgets |
| `MONGODB_URI` | the public market endpoints |
| `RATE_LIMIT_WEIGHT_PER_MINUTE`, `RATE_LIMIT_ORDERS_PER_DAY`, `RATE_LIMIT_WS_CONNECTIONS_PER_IP` | the published limits ([api-signing-and-limits.md](api-signing-and-limits.md)); what is set here is what `GET /v1/markets/info` reports |

### spot-matching-engine

| Variable | What it is |
|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | commands in, events out |
| `KAFKA_REPLICATION_FACTOR` | copies of each topic this app creates; 1 on a laptop, 3 in a deployment ([deployment.md](deployment.md)) |
| `ENGINE_PARTITION_COUNT`, `ENGINE_PARTITIONS` | how many command partitions exist, and which this instance owns; fixed once a market has traded ([command-partition.md](command-partition.md)) |
| `SNAPSHOT_BUCKET`, `SNAPSHOT_REGION`, `SNAPSHOT_S3_ENDPOINT`, `SNAPSHOT_S3_ACCESS_KEY`, `SNAPSHOT_S3_SECRET_KEY` | where order-book snapshots go. Unset, every one falls back to the local MinIO, which is right on a laptop and wrong in production: set the bucket and region, leave the endpoint and keys blank for real S3 with an instance role |
| `SNAPSHOT_REQUIRED` | `true`: refuse to start if the bucket is unreachable |
| `ENGINE_MANAGEMENT_PORT` | health and metrics |

### spot-settlement

| Variable | What it is |
|---|---|
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `FLYWAY_LOCATIONS` | MySQL: the only writer of balances, orders and the ledger |
| `KAFKA_BOOTSTRAP_SERVERS` | events in, settlements out |
| `KAFKA_REPLICATION_FACTOR` | copies of each topic this app creates; 1 on a laptop, 3 in a deployment ([deployment.md](deployment.md)) |
| `SETTLEMENT_DB_POOL_SIZE` | sized in [scalability.md](scalability.md) |
| `REDIS_HOST`, `REDIS_PORT` | a connection the shared rate limiter bean needs to start; unused here |
| `SETTLEMENT_MANAGEMENT_PORT` | health and metrics |

### market-data

| Variable | What it is |
|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | events in |
| `KAFKA_REPLICATION_FACTOR` | copies of each topic this app creates; 1 on a laptop, 3 in a deployment ([deployment.md](deployment.md)) |
| `MONGODB_URI` | candles, depth, tape, tickers |
| `MARKET_DATA_CONCURRENCY` | consumer threads ([scalability.md](scalability.md)) |
| `REDIS_HOST`, `REDIS_PORT` | as settlement: held, unused |
| `MARKET_DATA_MANAGEMENT_PORT` | health and metrics |

### spot-copy

| Variable | What it is |
|---|---|
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `FLYWAY_LOCATIONS` | MySQL: the copy tables |
| `KAFKA_BOOTSTRAP_SERVERS` | events in, commands out |
| `KAFKA_REPLICATION_FACTOR` | copies of each topic this app creates; 1 on a laptop, 3 in a deployment ([deployment.md](deployment.md)) |
| `REDIS_HOST`, `REDIS_PORT` | as settlement: held, unused |
| `COPY_MANAGEMENT_PORT` | health and metrics |

The share rate, follower limits and the leader's funding bounds are rows in
`copy_settings`, edited in the console ([copy-trading.md](copy-trading.md)).

### api-docs (build time)

| Variable | What it is |
|---|---|
| `EXCHANGE_NAME` | the page titles. No default |
| `api-docs/content/logo.svg`, `favicon.svg`, `apple-icon.png` | the site's brand files |

### nginx and docker compose

| Variable | What it is |
|---|---|
| `EXCHANGE_DOMAIN` | adds `app.`, `api.`, `docs.` and `admin.<domain>` beside the `*.localhost` names ([deployment.md](deployment.md)) |
| `ADMIN_ALLOWLIST` | the nginx file that decides who may open the staff console; unset, the strict one |
| `EXCHANGE_NAME`, `GOOGLE_CLIENT_ID` | compose reads these from the root `.env` (a link to `backend/.env`) and hands them to the builds and the apps above |

### Decided in the database, by staff

Markets and their limits, the fee schedule, per-customer overrides, order-limit caps and every
copy-trading setting are rows, edited in the staff console and audited there
([admin.md](admin.md), [fee-tiers.md](fee-tiers.md), [copy-trading.md](copy-trading.md)). The
first staff account comes from `create-admin`; nothing seeds one.

## Going live: what is not here

In the order an operator meets them.

1. **Custody, deposits and withdrawals.** The biggest piece, and deliberately absent. An
   integrator maps their custody onto `wallet.deposit` / `wallet.withdrawal` / `wallet.address`
   / `wallet.allowlist`, credits a confirmed deposit through `BalanceService.deposit` (the one
   path that moves a balance in from outside, in a transaction with its ledger row; the dev
   faucet stands in for it today), and builds the
   withdrawal flow: request, allowlist check, staff approval, signing, broadcast, confirmation,
   each a state on the row that exists for it. `can_withdraw` on API keys, the `WITHDRAWAL`
   profit-share trigger in copy trading and the treasury page are already shaped for it.
2. **A KYC provider.** `KycService` keeps the levels and the gate; the submission is the
   customer's own words and a staff reviewer. Document capture, liveness and screening belong to
   a provider; its webhook lands on the same approve/reject path the console uses
   (`KycService`, "a provider's webhook, or a reviewer").
3. **TLS and DNS.** Four hostnames, one certificate or a load balancer in front of nginx
   ([deployment.md](deployment.md)). `EXCHANGE_DOMAIN` adds the names to the config.
4. **A mail relay** with SPF and DKIM for `MAIL_FROM`; `MAIL_MODE=smtp`.
5. **Object storage** for engine snapshots, and Kafka with retention that fits: commands and
   events are kept forever by design, so a broker needs tiered storage or disk to match
   ([kafka-topics.md](kafka-topics.md), `CommandTopicConfig`).
6. **Markets and fees.** `db/seed` is a developer's twelve pairs and a sample schedule. A
   deployment lists its own markets (tick, step, limits, partition) and publishes its own fee
   levels from the console, before anyone trades.
7. **Staff.** `create-admin` for the first superadmin; the office and VPN addresses in
   `deploy/nginx/admin-allowlist.conf`; the rest from the console.
8. **Operations.** Metrics and alerting on the actuator endpoints, log shipping, backups for
   MySQL and MongoDB, a runbook for engine recovery ([matching-engine.md](matching-engine.md)).
   None of it is in the repo; the endpoints and the docs are.
9. **Legal.** Terms, privacy, jurisdiction gating. The footer carries one risk sentence and
   no documents behind it.
10. **The advanced chart.** The trade screen draws candles and volume with the open-source
    lightweight-charts library (`web/src/features/trade/Chart.tsx`): twelve intervals, no
    drawing tools, no indicators. The full TradingView Charting Library -- indicators, drawings,
    saved layouts, the chart traders expect -- is licensed from TradingView and is not
    redistributable, so it cannot ship here. An operator who has the licence drops the library
    in and implements its datafeed against the same candles endpoint and `kline` channel the
    current chart reads; nothing on the server changes.
11. **The brand.** Four SVGs, two favicons, one name. Everything else already reads it.
