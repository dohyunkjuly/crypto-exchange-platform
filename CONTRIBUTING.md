# Running the stack

Docker Desktop and any JDK 21 or newer. Node 22 or newer for the frontend.

Read [AGENTS.md](AGENTS.md) before changing code, plus the conventions for whichever side you
are touching: [docs/STYLE.md](docs/STYLE.md).

## Where things are

```
backend/
├── apps/                       what deploys; each has a README
│   ├── spot-open-api/          :8080  the public REST and WebSocket API
│   ├── web-bff/                :8081  backend-for-frontend, serves web/ only
│   ├── admin-bff/              :8083  backend-for-frontend, serves admin/ only. Staff, its own login
│   ├── spot-matching-engine/          the order book. Kafka in, Kafka out.
│   ├── spot-settlement/               the sole writer of order/wallet state
│   ├── spot-copy/                     copy trading: a leader's filled order becomes each follower's
│   └── market-data/                   candles, tickers and depth from the event stream
└── libs/                       what they share; see its README
    ├── common/                 money, ids, crypto primitives -- plain Java, no Spring
    ├── contracts/              protobuf
    ├── core/                   shared HTTP response/error machinery
    ├── persistence/            Flyway migrations + JPA deps. No entities.
    └── domain/                 user/ wallet/ fee/ spot/ marketdata/ copy/
web/                            Next.js (:3000), the customer app
admin/                          Next.js (:3001), staff only; talks to admin-bff
api-docs/                       the public API's documentation site
bots/                           Python. For testing: fills the books and trades on the local stack
deploy/nginx/                   the edge: one nginx, four hostnames, the staff allowlist
docs/
├── STYLE.md                    the style rules the code cannot show you
├── assets/                     what the README shows
└── reference/                  one topic per file: engine, settlement, locked funds, sockets, …
docker-compose.yml              kafka, mysql, mongo, redis, minio, mailpit; --profile full adds every app and nginx
```

One split at the top: `apps/` deploys, `libs/` is shared. Gradle paths follow it:
`./gradlew :apps:web-bff:bootRun`.

## 1. Secrets, once

Both encryption keys are **required**. The apps crash at startup without them.

```bash
cp backend/.env.example backend/.env
openssl rand -base64 32     # -> API_KEY_ENCRYPTION_KEY
openssl rand -base64 32     # -> MFA_ENCRYPTION_KEY   (a separate key, deliberately)
ln -s backend/.env .env     # compose reads ${...} from the root only; one file, not two

cp web/.env.example web/.env.local
cp admin/.env.example admin/.env.local
```

`EXCHANGE_NAME` in `backend/.env` is the operator's name everywhere: the apps read the file, and
compose passes it to the web, admin and api-docs image builds through the link.

## 2. Infrastructure

```bash
docker compose up -d
docker compose ps          # wait for (healthy)
```

| | Host port | Note |
|---|---|---|
| Kafka | 9092 | in-container listener is `kafka:29092` |
| MySQL | **3308** | not 3306, so it never collides with a local MySQL |
| MongoDB | **27018** | market-data's candles and depth |
| Redis | 6379 | web-bff's rate limiter |
| MinIO | 9000, 9001 | engine snapshots; console on 9001 |
| Mailpit | 1025, 8025 | SMTP + web inbox at http://localhost:8025 |

Topics are created by the apps themselves (`EventTopicConfig`, `SettlementTopicConfig`); the
command topic also by the `kafka-topics` one-shot in the compose file, so a stack without the
engine has it with the right partitions before anything produces to it.

```bash
docker compose down        # stop, keep data
docker compose down -v     # delete data too. Migrations run again from scratch
```

## Dev accounts

With the `dev` profile on (the compose stack sets it), web-bff creates three accounts at startup
if they do not exist, all with the password `dev-password`:

| Email | Has |
|---|---|
| `alice@example.com` | verified email, KYC INTERMEDIATE, 2FA, an API key, 1,000,000 of every asset |
| `bob@example.com` | the same without the API key |
| `carol@example.com` | verified email and the balances only, so every gate can be seen |

The authenticator secrets are fixed, so set them up once in any TOTP app and they survive every reset:

| Account | Authenticator secret |
|---|---|
| alice | `ALICEDEVSECRET2345ALICEDEVSECRET` |
| bob | `BOBDEVSECRET234567BOBDEVSECRET23` |

Nothing here is a migration: `docker compose down -v` wipes the accounts and the next start
recreates them. See `DevSeeder` in web-bff.

## Staff accounts

Nothing seeds an admin, on any profile: the first one is made with the CLI built into admin-bff,
which is the only way to become staff anywhere ([docs/reference/admin.md](docs/reference/admin.md)).

```bash
docker compose --profile full run --rm admin-bff create-admin --email ops@example.com --role SUPERADMIN
```

It prompts for the password, hidden as typed. Log in at http://localhost:3001 and enrol an
authenticator at the first login; every write in the console needs a code from it. Roles are
`VIEWER`, `OPERATOR` and `SUPERADMIN`; only a `SUPERADMIN` makes more admins, from the console.
`docker compose down -v` wipes staff with everything else. The twelve markets and the fee schedule come from
`backend/libs/persistence/src/main/resources/db/seed/`, applied wherever `FLYWAY_LOCATIONS`
names it: `backend/.env`, the compose stack and the tests. A real deployment leaves it unset.

## 3. The apps

From `backend/`, each in its own terminal:

```bash
./gradlew :apps:web-bff:bootRun               # :8081  the one the frontend needs
./gradlew :apps:spot-matching-engine:bootRun  # :8090  health only
./gradlew :apps:spot-settlement:bootRun       # :8091  health only
./gradlew :apps:market-data:bootRun           # :8092  health only
./gradlew :apps:spot-copy:bootRun             # :8093  health only
./gradlew :apps:spot-open-api:bootRun         # :8080  the public API
```

Frontend: `cd web && npm install && npm run dev`, then open http://localhost:3000

A full order round trip needs **web-bff, matching-engine and spot-settlement**. Add
**market-data** for charts and depth, and **spot-copy** for copy trading.

### Or everything at once, in Docker

```bash
cp backend/.env.example backend/.env          # the two encryption keys must be filled in
docker compose --profile full up -d --build
```

One command, the whole stack, no JDK or Node needed. `web-bff` reads `backend/.env` for the
encryption keys. Every other value is overridden with compose service names.

The way in is nginx on port 80, by hostname ([docs/reference/deployment.md](docs/reference/deployment.md)):

| | |
|---|---|
| http://app.localhost | the exchange |
| http://api.localhost | the public API |
| http://docs.localhost | its documentation |
| http://admin.localhost | the staff console. 403 with an explanation? Your browser reaches Docker from a public address (a VPN does this): set `ADMIN_ALLOWLIST=./deploy/nginx/admin-allowlist.local.conf` in `backend/.env` |

`*.localhost` needs no `/etc/hosts` entry in Chrome or Firefox. The apps' own ports are bound to
`127.0.0.1` for the bots and the integration tests (`8080`, `8081`, `8083`); the Next.js
containers publish nothing, since their images are built with `NEXT_PUBLIC_*_URL=/` -- the page's
own origin -- and only work behind nginx.

**`NEXT_PUBLIC_BFF_URL` is a build argument, not a runtime variable.** Next inlines it into the
client bundle at build time. Changing it means rebuilding the `web` image; the same goes for
`NEXT_PUBLIC_ADMIN_BFF_URL` and `admin`.

```bash
docker compose --profile full down            # stop everything; the data is kept
docker compose --profile full down -v         # and delete the data: a clean slate
docker compose --profile full up -d           # start again, after either. --build if the code changed
docker compose --profile full logs -f web-bff
```

## 4. Build and test

```bash
cd backend
./gradlew build                   # compile + test everything
./gradlew :libs:common:test       # one module

cd ../web
npm test                          # vitest
npm run build                     # type-checks as well
```

Integration tests (`*IT`) need `docker compose up -d`. Without it they **abort** rather than
fail. They share one MySQL, so each cleans up after itself.

Performance benchmarks are not part of `build`. `./gradlew perf` runs them against the full
stack and writes [docs/reference/performance-report.md](docs/reference/performance-report.md);
what to expect and what the numbers mean: [docs/reference/performance.md](docs/reference/performance.md).
