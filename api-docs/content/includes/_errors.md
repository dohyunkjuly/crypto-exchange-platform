# Errors

> An error envelope over REST

```json
{ "status": 400, "code": 5008, "data": null,
  "message": "Quantity is out of range or off the step size" }
```

> The same code in a WebSocket error frame

```json
{ "type": "error", "id": 7, "code": 7005, "message": "Unknown channel" }
```

Here is the Error Code


## REST API errors

| Code | HTTP status | Message |
|---|---|---|
| `1000` | 400 | Validation failed, or the body is not valid JSON; the message says which. |
| `1001` | 404 | No such endpoint. |
| `1002` | 405 | Method not allowed on this endpoint. |
| `2101` | 401 | Unknown API key. |
| `2102` | 401 | Bad signature. |
| `2103` | 401 | Timestamp is outside the receive window. |
| `2104` | 403 | This IP is not on the key's allowlist. |
| `2105` | 403 | The key lacks the permission for this request. |
| `2106` | 401 | API key revoked or expired. |
| `2107` | 403 | Identity verification is required to trade. |
| `2108` | 403 | A second factor must be active on the account. |
| `2109` | 403 | The account's email is not verified. |
| `2110` | 403 | The account is frozen. |
| `2111` | 403 | Only a master account's key may manage sub-accounts. |
| `2112` | 409 | No more sub-accounts can be created. |
| `2113` | 404 | No such sub-account. |
| `2114` | 403 | That sub-account is frozen. |
| `2115` | 403 | Trading is not available on this account. |
| `3001` | 429 | Too many requests. `Retry-After` says how long to wait. |
| `4001` | 400 | Insufficient balance. |
| `4002` | 404 | No such asset. |
| `5001` | 404 | No such symbol. |
| `5002` | 400 | Symbol is not open for trading. |
| `5003` | 400 | Market orders are disabled for this symbol. |
| `5004` | 400 | Stop and take-profit orders are disabled for this symbol. |
| `5005` | 400 | This time in force is not supported for this order. |
| `5006` | 400 | Price is required and must be on the tick size. |
| `5007` | 400 | Stop price is required (unless trailing) and must be on the tick size. |
| `5008` | 400 | Quantity is out of range or off the step size. |
| `5009` | 400 | A market-firing BUY requires `quoteOrderQty`. |
| `5010` | 400 | `quoteOrderQty` is not valid for this order. |
| `5011` | 400 | `trailingDeltaBps` must be between 10 and 2000, and only on a conditional order. |
| `5012` | 400 | Order notional is out of the symbol's allowed range. |
| `5013` | 400 | Too many open orders. |
| `5014` | 400 | Too many open conditional orders. |
| `5015` | 404 | No such order. |
| `5016` | 409 | Order is no longer open and cannot be cancelled. |
| `5017` | 400 | The time range must run forwards and span at most 90 days. |
| `5018` | 503 | This market is not available right now. |
| `5020` | 400 | `EXPIRE_BOTH` cannot be combined with `FOK`. |
| `5021` | 400 | `side` is not `BUY` or `SELL`. |
| `5022` | 400 | Unknown `orderType`. |
| `5023` | 400 | Unknown `timeInForce`. |
| `5024` | 400 | `selfTradePreventionMode` is not `EXPIRE_TAKER`, `EXPIRE_MAKER` or `EXPIRE_BOTH`. |
| `5025` | 409 | This order can no longer be amended. |
| `5026` | 409 | An amend of this order is still being processed. |
| `5027` | 400 | The amend changes nothing. |
| `5028` | 400 | That field cannot be amended on this order. |
| `5029` | 403 | A copy account is traded by copying, not by hand. |
| `5030` | 400 | This account cannot trade this market. A lead account trades USDT markets only. |
| `5031` | 503 | Trading on this market is paused: no matching engine is running it right now. Nothing is wrong with the order; retry in a few seconds, and read `paused` on `GET /v1/markets/info`. Cancels are never refused. |
| `6001` | 400 | Unknown candle interval. |
| `6002` | 404 | No market data for this symbol yet. |
| `6003` | 400 | Unsupported price grouping. |
| `9000` | 500 | Internal error. |

## WebSocket errors

The `auth` message can also answer with `2101` to `2106`.

| Code | Message |
|---|---|
| `7001` | Malformed message; expected JSON. |
| `7002` | A symbol is required. |
| `7003` | Unknown action; use `subscribe`, `unsubscribe` or `auth`. |
| `7004` | Too many subscriptions on one connection. |
| `7005` | Unknown channel. |
| `7006` | Unknown or missing interval. |
| `7007` | Unsupported price grouping for this market. |
| `7008` | This connection is already authenticated. |
| `7009` | Too many messages on this connection. |
| `7010` | Too many connections. |
| `9001` | Internal error. |
