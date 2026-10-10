# spot-open-api: signing, gates and rate limits

The internal side of the public API. What a bot developer needs is on the developer site in
`api-docs/`; this is what the site does not say: what the app is built on, how the signing
filter is ordered and why, and how the limits are counted.

## What it builds on

open-api adds no domain logic. Every capability is a shared service in `libs/domain/*` that
web-bff already used; open-api is a second edge with a different credential.

| Need | Where it lives | Notes |
|---|---|---|
| Symbols, filters, groupings | `SymbolCatalog` (spot) | moved out of web-bff when open-api became the second user |
| Ticker, candles, depth, trade tape | `TickerService`, `CandleService`, `DepthService`, `TradeTapeService` (marketdata) | |
| Place, amend, cancel | `OrderService` (spot) | publishes to the market's `command_partition` |
| Open orders, history, by id | `OrderQueryService` (spot) | `TimeRange` caps history at 90 days |
| My trades with fees | `TradeQueryService.history` (spot) | a negative fee is a rebate |
| Balances | `BalanceService.list` (wallet) | |
| Fee rates and tier | `FeeQueryService.summaryFor(userId, SPOT)` (fee) | |
| API keys | `ApiKeyService`, `UserApiKey` (user) | HMAC-SHA256 keys, AES-GCM secret, read/trade/withdraw permissions, CIDR allowlist, expiry, revocation |
| Trading gate | `EligibilityService.requireCanTrade` (user) | see [trading-gate.md](trading-gate.md) |
| Rate limiting | `RateLimiter` (core, Redis) | per-endpoint weights; web-bff uses weight 1 |
| Response and error classes | `HttpApiResponse`, `HttpApiExceptionHandler` (core) | one envelope for both apps |
| Live frames | `stream` packages in marketdata and spot | see [sockets.md](sockets.md) |

Deliberately missing: deposits, withdrawals and addresses ([white-label.md](white-label.md)).
`/v1/wallet` is balances only. `can_withdraw` exists on the key and no endpoint uses it.

## How it differs from web-bff

- **It is a public promise.** Fields are added, never renamed or removed. A breaking change is
  `/v2`. web-bff changes shape with the UI and must never be reused as a public surface.
- **Response records are open-api's own copies of web-bff's views**, field for field, so a
  public shape cannot change because a UI view did.
- **Same envelope, money and time formats** as web-bff, so a client that reads one reads the
  other.
- **Its own error codes** in `OpenApiErrorCode`. Where a domain exception means the same as in
  web-bff, the same number is used by convention: a courtesy to whoever reads two logs, not a
  shared enum. See [error-codes.md](error-codes.md).
- **Keys are created on the web UI only**, behind INTERMEDIATE KYC and a TOTP step-up. A bot
  with a stolen key must not be able to make more keys.

## The signing filter, in order

`X-ACCESS-SIGN` is `HMAC-SHA256(secret, timestamp + method + pathWithQuery + body)`: the body
is signed rather than the query alone, because orders are JSON bodies here and an
unsigned body could be replayed with edits. No brand in the header names: they end up in every
client library written against a white-label deployment.

`ApiKeyAuthInterceptor` runs behind a body-caching filter, since the signature covers the body
and a servlet reads it once. Each step has its own error so a client can tell what to fix:

1. Look the key up by `api_key`: indexed, cached in-process for 30 seconds including negative
   results. Missing, revoked or past `expires_at`: 2101 or 2106.
2. `|now − timestamp| ≤ recvWindow`, else 2103. Rejects replays and clock drift together.
3. The IP against the CIDR allowlist when one is set, else 2104.
4. The signature, constant-time compare, else 2102.
5. The permission for this endpoint: `can_read` for reads, `can_trade` for placing, amending
   and cancelling, else 2105.
6. Load the `User`. For placing and amending only, `EligibilityService.requireCanTrade`:
   2107 KYC, 2108 MFA, 2109 email. Never for cancel or read.
7. Write `last_used_at` at most once per key per minute, in its own short transaction.

Two layers on purpose: the key decides what this credential may do, eligibility decides what
this account may do right now. A revoked key fails at step 1; a key whose owner's KYC lapsed
fails at step 6, and only for placing.

## Rate limits

| Bucket | Key | Default | Applies to |
|---|---|---|---|
| request weight | `ratelimit:oapi:weight:{ip}` | 1200 per minute | every endpoint, weighted (`@Weight`; depth scales with `limit`) |
| orders | `ratelimit:oapi:orders10s:{apiKey}`, `orders1d:{apiKey}` | 100 per 10 s and 1000 per day | placing and amending (`@CountsAsOrder`). A cancel never spends it |

Weight is counted per client IP because it is checked first, before the signature: at that
point the key header is unverified text a flood could vary freely, and the address is the one
thing a caller cannot choose. The order budget needs an identity, so it runs after the gate,
per key. A 429 still counts, so a retry loop makes its own wait longer. Every response carries
`X-USED-WEIGHT-1M`; a placement also carries `X-ORDER-COUNT-10S` and `X-ORDER-COUNT-1D`.

## 202 on placement and amend

Funds are locked and the command is durable at that point; matching happens later. A client
that times out must read, never retry: a retry without `clientOrderId` is a second order. With
it, a retry returns the original order. Cancel and amend by client id exist because a bot that
timed out on `POST` holds only the id it chose.

## Open questions

- `GET /v1/markets/tickers` is public at weight 20. Keep it, or drop it in favour of the
  socket's `ticker` channel? Keeping both is the usual answer.
- Ed25519 keys. The table supports them and `UserApiKey.ed25519` exists, but the creation flow
  with a client-side keypair is not built. HMAC only for now; the filter is written so a second
  verifier slots in on `key_type`.
