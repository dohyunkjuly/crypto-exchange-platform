---
title: "{name} Spot API"

language_tabs:
  - shell: HTTP
  - python: Python
  - javascript: TypeScript

includes:
  - authentication
  - markets
  - account
  - sub_accounts
  - orders
  - trades
  - websocket
  - errors
---

# Introduction

> Base URLs

```text
REST       https://api.example.com/v1
WebSocket  wss://api.example.com/v1/ws
```

> Response envelope

```json
{
    "status": 200,
    "code": 0,
    "data": {},
    "message": "OK"
}
```

REST for market data, account data and order entry; one WebSocket for live market data and the
account's own orders, fills and balances. All endpoints are under `/v1`.

Every response carries `status`, `code`, `data` and `message`. `code` is `0` on success.

Prices and quantities are decimal strings. Times are ISO-8601 UTC. Timestamps sent in a request
are epoch milliseconds.

## Generating an API key

API keys are created on the web UI under **API Management**. Creating one needs:

- Identity verification at the `INTERMEDIATE` level
- A second factor on the account, and a current code from it at creation time

A key has a label, permissions, an optional IP allowlist and an optional expiry. The secret is
shown once, at creation, and cannot be shown again. Revoking a key needs no second factor, so a
leaked key can be killed at once.

## API key permissions

| Permission | Allows |
|---|---|
| Read | Account, balances, orders, trades, and the private WebSocket frames |
| Trade | Placing and cancelling orders |
| Withdraw | Nothing on this API. Withdrawals are not exposed |

Every request checks the key's permission before anything else about the account. Placing an
order also needs the account to be allowed to trade: a verified email, identity verification at
the `BASIC` level and an active second factor. `GET /v1/account` reports all of this.

## General info

The rules for placing orders are as follows:

- The maximum number of open orders per market is 200 by default. A market may set its own limit
- The maximum number of open conditional orders per market is 50
- The maximum number of open orders per account is 1000
- The maximum number of open conditional orders per account is 200
- An open order is one in `PENDING_TRIGGER`, `NEW`, `PARTIALLY_FILLED` or `PENDING_CANCEL`

The rules for trading are as follows:

- Placing and cancelling answer `202`. The order is queued and the engine decides the outcome.
  The result arrives on the WebSocket or on the next read
- An order never trades against another order of the same account. The default
  self-trade prevention mode is `EXPIRE_MAKER`, and `EXPIRE_BOTH` cannot be combined with `FOK`
- A conditional order whose trigger already holds at placement is rejected by the engine
- A market that has never traded rejects every conditional order, since there is no last price
- A `clientOrderId` sent twice returns the original order instead of placing a second one

The rules for the returned data are as follows:

- `code` is `0` on success. On a failure it is the error code, and `data` is `null`
- Match on `code`. `message` describes the failure and may be reworded
- A field that does not apply to an order is `null`, never `0`

## Transaction timeouts

A signed request carries `X-ACCESS-TIMESTAMP`, the time it was made in epoch milliseconds. The
server refuses it with `2103` when its own clock is more than `X-ACCESS-RECV-WINDOW` milliseconds
away from that time, in either direction. The default window is `5000` and the maximum is
`60000`.

A request that reaches the server late, through network delay or a retry, is therefore
discarded rather than executed. Keep the client clock synchronised, and raise the window only
when a slow network makes the default too tight.

## Rate limits

| Limit | Counted per | Default | Applies to |
|---|---|---|---|
| Request weight | IP address | 1200 per minute | Every request, weighted per endpoint |
| Orders | API key | 100 per 10 seconds, 1000 per day | Placing an order |

Every response carries `X-USED-WEIGHT-1M`. A placement also carries `X-ORDER-COUNT-10S` and
`X-ORDER-COUNT-1D`. A refused request answers `429` with `Retry-After`, the seconds to wait, and
still counts.
