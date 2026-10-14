# crypto-exchange-platform

> Microservices, Java and Spring Boot on the backend, and Next.js on the frontend. you can run the whole thing with `docker compose` command. For quick start you can check [CONTRIBUTING](CONTRIBUTING.md).

I built this project during my freetime for my portfolio with Claude. I have experience working in crypto exchange, and I aim this project to be a white-label solution for businesses who would like to start their own exchange.

There are some features which are not built or completed to go fully alive. These includes:
1. Custody, withdrawal, and deposits (Blockchain transfer)
2. KYC Provider. 
3. Legal, such as terms of services.
4. For more details please look at [White-Label](docs/reference/white-label.md) 

> If you need a demo or have any inquiry about the project you can contact <dohyun.k.july@gmail.com>. I can speak both Korean and English.

I have tried to write down every decision that is not obvious from the code, in
[docs/reference/](docs/reference/).

![Trade screen](docs/assets/trade.png)

![Home](docs/assets/home.png)

## Contents

- [What is inside](#what-is-inside)
- [Architecture](#architecture)
- [Screenshots](#screenshots): [web pages](#web-pages) · [admin console](#admin-console) · [Open API docs](#open-api-docs)
- [Performance](#performance)
- [Running it](CONTRIBUTING.md)
- [Reading further](#reading-further)

## What is inside

### Trading

- [x] Order types: limit, market, stop-loss, stop-loss-limit, take-profit, take-profit-limit, limit-maker (post only). Time in force GTC, IOC, FOK.
- [x] Trailing stops (10–2000 bps), with an optional activation price.
- [x] Amend Order for resting / trigger orders.
- [x] Cancel, cancel by client id, cancel-all.
- [x] Self-trade prevention (expire taker, maker or both).
- [x] Per-market sizing rules: tick, step, min/max quantity, min/max notional and so on.
- [x] Candlestick chart with 12 intervals (1m to 1M) and volume
- [x] Open orders, trigger orders, order history and trade history.

### Open / Public API

- [x] REST under `/v1`: markets, depth, trades, candles, orders (place, amend, cancel, open, history), my trades, balances, sub-accounts.
- [x] Rate limits by request weight per IP and by order count per key, reported in response headers.
- [x] WebSocket `/v1/ws`: ticker, trade, depth (whole book or absolute deltas), kline, and private user channel for real time updates for order and balance changes.
- [x] A documentation site in [api-docs/](api-docs/README.md).

### Accounts

- [x] Email registration with a verification code, Google sign-in.
- [x] TOTP two-factor.
- [x] KYC in levels (basic, intermediate, advanced) with a staff review (Incomplete on purpose).
- [x] API keys: HMAC-SHA256, read/trade permissions, IP or CIDR allowlist.
- [x] Sub-accounts: up to 20 per master, their own balances, orders and keys, gated by the master's KYC and 2FA.
- [x] Fee tiers from the family's trailing 30-day volume, recounted nightly from trades. Staff can set a custom rate per customer on admin console.

### Copy trading

- [x] Anyone eligible becomes a lead trader in one step by funding a lead account.
- [x] Followers copy at a fixed ratio or a fixed amount per order.
- [x] Buys are limit-IOC at the leader's average price with a slippage cap each follower sets for themselves. Sells sell the same percentage the leader sold. A copy that fails is not retried and repeated failures close the copy.
- [x] Profit share on realized profit only, net of fees, at the rate frozen when the follow began, settled weekly, on close and on withdrawal. Staff can give a leader a rate of their own.
- [x] Leader figures recomputed nightly: 7/30/90-day return, earnings and max drawdown, copy success rate, funds copying.

### Admin/Staff console

- [x] Dashboard: sign-ups, trades, volume and fees against yesterday, per market.
- [x] Customers: search freeze, suspend trading, revoke sessions and keys.
- [x] Fee, order limits overrides for customers and markets.
- [x] KYC review (In complete)
- [x] Markets listing (list/delist/cancel-only a market).
- [x] Copy trading leaders and settings.
- [x] Orders, trades and ledger, read-only and filtered.
- [x] Exchange's own two accounts (One for fees, the other for treasury). Staff move fees to the treasury and pay customers out of it, with an authenticator code and an audit trail.
- [x] Audit log history.


## Architecture

![Architecture](docs/assets/architecture.png)

#### 1. General Structure

The spot matching engine is just memory. It has no database and no HTTP API. It consumes commands.v1 and produces events.v1. Everything a user sees, such as balances, the order book and candles, is built from those events by other services.

The core idea is that a partition is only ever processed by one thread. Each symbol is assigned to a partition by configuration, and each engine instance owns its partitions by configuration, not by Kafka. To scale, run more engine instances, each owning fewer partitions.

Snapshots are stored in S3, or MinIO locally. On restart the engine opens the latest snapshot, re-reads the commands after it, and rebuilds the order book. The engine commits an offset only after its events are published, so nothing is ever lost in a crash; a command may be published twice, but identically, and every consumer dedupes it. The engine also publishes a heartbeat every second. When it stops, web-bff, spot-open-api and spot-copy stop accepting new orders on its markets, while cancels still go through.

There are five consumers of events.v1. Settlement writes orders, trades, balances and the ledger to MySQL. Market-data builds the order book, candles, tickers and the trade tape in MongoDB. Spot-copy turns a copy-trade leader's orders into each follower's order. Web-bff and spot-open-api push order updates and the public market data over websockets. Balance updates reach those sockets from settlements.v1, after settlement has written them.

#### 2. Delivery: the outbox and idempotency (Kafka At-least once Delivery)

Every hop in this system delivers at least once, but the messages are processed exactly once. Kafka can and will deliver a message twice, so outbox pattern and idempotency were used to solve this problem.

When user places an order, the API locks your funds, saves the order and writes the command to an outbox table, all in one DB transaction. A publisher sends the command to Kafka within milliseconds and a sweeepr re-sends anything still unsent after few seconds. An order the API has accepted therefore always reaches the engine, even if Kafka was down or the API crashed right after returning response to the client. Hence a command can be sent twice. 

How the system handles depulication at every step.

- The engine remembers the recent command ids for each market (saved in the snapshot) and ignores a repat. It commits its offset only after its events are published. Also, if the engine fails to publish events couple of times after processing an order multiple times, it will shut down and restart so it gurantees publishing events after processing an incoming order.

- Settlement checks each trade by (symbol, sequence), each order's fills by (order, trade), and each balance movement by an idempoentecy key in the ledger. A redelivered batch changes nothing.

- Market-data keeps a sequence watermark on every order book and candle, so depulicates does nothing.

The details, hop by hop, and the known gaps are [delivery-guarantees](docs/reference/delivery-guarantees.md) 

#### 3. Scalability and limitations

How it scales. The engine scales by partitions and instances. One engine thread handles about 4,200 orders a second end to end, and the limit is the Kafka publish-and-confirm round trip on every order, not the matching itself, which takes microseconds. To go further, split the markets across more partitions and run more engine instances, each owning its own partitions.

Everything around the engine is easier. Web-bff and spot-open-api hold no state, so you add instances behind nginx. Market-data and the socket fan-outs read the same event stream, so they scale out the same way. Settlement processes a batch in parallel, grouped by balance row, and committed about 2,500 fills a second in my tests, with balances final about 0.4 seconds after the fill.

Limitations.

- One market can't go faster than one engine thread. A market lives on one partition and can't be split, so a single market tops out around 4,200 orders a second. Going past that means changing how the engine publishes.
- A market can't move partitions once it has traded. Choose partitions for busy markets before they launch.
- One MySQL. Settlement is the single writer of balances and orders, so the database is the ceiling for fills. The domains share no foreign keys, so they could move to separate databases later, but nothing does that today.
- No multi-region setup. Kafka replication is a setting (three brokers in production). Everything else runs in one region.
- Copy trading multiplies orders. A leader with 500 followers turns one order into 500, so the follower cap is also the copier's load cap. Copies go in eight at a time and in a new random order each time, so no follower is always the one who misses the price, but the last ones in still see a book the earlier ones moved. Placing one combined order and splitting its fills would make it fair for everyone, and is not built.
- Measured on my laptop. Measure on your own hardware before sizing anything. The details are in [scalability](docs/reference/scalability.md).

## Screenshots

FYI Some of the screenshots are taken with seeded data.


### Web pages

| | |
|---|---|
| ![Trade](docs/assets/trade.png) Trade | ![Copy trading](docs/assets/copy.png) Copy trading |
| ![Leader profile](docs/assets/copy-profile.png) A leader | ![Lead account](docs/assets/copy-lead.png) Becoming a lead trader |
| ![Log in](docs/assets/login.png) Log in | ![Dashboard](docs/assets/dashboard.png) Account |
| ![Assets](docs/assets/assets.png) Assets | ![Orders](docs/assets/orders.png) Orders |
| ![Security](docs/assets/security.png) Security | ![Identification](docs/assets/identification.png) Identification |
| ![API keys](docs/assets/api-keys.png) API keys | ![Sub-accounts](docs/assets/sub-accounts.png) Sub-accounts |
| ![Transfer](docs/assets/sub-transfer.png) Transfer between accounts | |

### Admin console

| | |
|---|---|
| ![Login](docs/assets/admin-login.png) Staff login, TOTP at first sign-in | ![Dashboard](docs/assets/admin-dashboard.png) Dashboard |
| ![Customers](docs/assets/admin-users.png) Customers | ![Customer file](docs/assets/admin-users-detail.png) A customer's file |
| ![KYC review](docs/assets/admin-kyc.png) KYC review | ![Markets](docs/assets/admin-markets.png) Markets |
| ![Market](docs/assets/admin-markets-1.png) A market | ![Fees](docs/assets/admin-fees.png) Fee schedule |
| ![Orders](docs/assets/admin-orders.png) Orders | ![Order](docs/assets/admin-orders-detail.png) An order |
| ![Trades](docs/assets/admin-trades.png) Trades | ![Ledger](docs/assets/admin-ledger.png) Ledger |
| ![Treasury](docs/assets/admin-treasury.png) Treasury | ![Copy leaders](docs/assets/admin-copy-leaders.png) Copy leaders |
| ![Copy settings](docs/assets/admin-copy-settings.png) Copy settings | ![Audit log](docs/assets/admin-audit.png) Audit log |
| ![Admins](docs/assets/admin-admins.png) Staff | |

### open-api-docs

| | |
|---|---|
| ![Introduction](docs/assets/api-docs.png) Introduction | ![Signing](docs/assets/api-docs-signing.png) Signing a request |
| ![Place order](docs/assets/api-docs-place-order.png) Place order | ![WebSocket](docs/assets/api-docs-websocket.png) WebSocket |

## Performance

Measured on my laptop against the running stack by `./gradlew perf`. The details are in
[performance.md](docs/reference/performance.md),
[performance-report.md](docs/reference/performance-report.md),
[scalability.md](docs/reference/scalability.md).

| | |
|---|---|
| Orders per second, one engine instance | about 4,200, end to end over Kafka; the exchange scales by adding instances |
| Click to fill, through the website | 22 ms at the median, 112 ms at p99 |
| A signed order over the API | 37 ms at the median, 108 ms at p99, ten keys at once |
| Market sockets served by one web-bff | 2,000, every one seeing a fresh trade within seconds |
| Fills written to the database | about 2,500 a second; balances final about 0.4 s after the fill |
| Memory | about 530 bytes per resting order; two million orders in about 1 GB |
| Back after a crash | about 11 s to replay 30,000 unsnapshotted commands |
| 5,000 stop orders firing on one trade | about 2.5 s during which that market waits |

## Reading further

| | |
|---|---|
| [CONTRIBUTING.md](CONTRIBUTING.md) | running it locally, and a map of the repository |
| [AGENTS.md](AGENTS.md) | the rules. Read before changing anything |
| [docs/STYLE.md](docs/STYLE.md) | the style rules whose reason is not visible in the code |
| [docs/reference/white-label.md](docs/reference/white-label.md) | what to configure to run it as your own exchange, and what is not here |
| [docs/reference/matching-engine.md](docs/reference/matching-engine.md) | the order book: startup, recovery, snapshots, amend, failure |
| [docs/reference/kafka-topics.md](docs/reference/kafka-topics.md) · [command-partition.md](docs/reference/command-partition.md) | the topics and what each keeps; how markets map to partitions |
| [docs/reference/delivery-guarantees.md](docs/reference/delivery-guarantees.md) | what happens when a process dies or a message arrives twice, hop by hop, and the gaps |
| [docs/reference/settlement-pipeline.md](docs/reference/settlement-pipeline.md) · [locked-funds.md](docs/reference/locked-funds.md) | how a batch of events becomes rows and balances; when funds are locked and released |
| [docs/reference/order-types.md](docs/reference/order-types.md) · [trading-gate.md](docs/reference/trading-gate.md) | every order type and its sizing rules; who may trade, and when a market is paused |
| [docs/reference/api-signing-and-limits.md](docs/reference/api-signing-and-limits.md) · [sockets.md](docs/reference/sockets.md) · [depth-fold.md](docs/reference/depth-fold.md) | the public API's insides |
| [docs/reference/copy-trading.md](docs/reference/copy-trading.md) | lead and copy accounts, the copier, the leader's figures, the profit share |
| [docs/reference/sub-accounts.md](docs/reference/sub-accounts.md) · [fee-tiers.md](docs/reference/fee-tiers.md) · [treasury.md](docs/reference/treasury.md) | accounts, fees and the exchange's own money |
| [docs/reference/credentials.md](docs/reference/credentials.md) · [frontend-session.md](docs/reference/frontend-session.md) | how secrets and sessions are stored; how the browser talks to web-bff |
| [docs/reference/money.md](docs/reference/money.md) · [migrations.md](docs/reference/migrations.md) · [entities.md](docs/reference/entities.md) · [error-codes.md](docs/reference/error-codes.md) | the rules that break silently or cannot be undone: money, schema, entities, error codes |
| [docs/reference/admin.md](docs/reference/admin.md) | the staff app: what staff can do, and the rules |
| [docs/reference/deployment.md](docs/reference/deployment.md) | the edge: one nginx, four hostnames, where TLS goes, what stays private |
| [docs/reference/scalability.md](docs/reference/scalability.md) | how it scales, with the measured numbers, and where the ceilings are |
| [docs/reference/performance.md](docs/reference/performance.md) · [performance-report.md](docs/reference/performance-report.md) | what the numbers mean and how to measure them; the latest run |
| [api-docs/](api-docs/README.md) | the developer documentation site |
| [bots/](bots/README.md) | bots that trade on the local exchange, for testing |

## A word of caution

You can use the code for free, but I am not responsible for any financial damanges caused by the flaw or bugs in the system.

Copyright 2026 Dohyun. Licensed under [Apache 2.0](LICENSE).
