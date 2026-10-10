# WebSocket

> Connect

```text
wss://api.example.com/v1/ws
```

> Request

```json
{
    "id": 1,
    "action": "subscribe",
    "channel": "trade",
    "symbol": "BTCUSDT"
}
```

> Reply

```json
{
    "type": "subscribed",
    "id": 1,
    "data": {
        "channel": "trade",
        "symbol": "BTCUSDT"
    }
}
```

> Push

```json
{
    "type": "trade",
    "data": {
        "symbol": "BTCUSDT",
        "sequence": 18422,
        "price": "75502.00",
        "quantity": "0.01000",
        "quoteQuantity": "755.02",
        "takerSide": "SELL",
        "time": "2026-09-14T02:59:58.120Z"
    }
}
```

> Error

```json
{
    "type": "error",
    "id": 1,
    "code": 7005,
    "message": "Unknown channel: trades"
}
```

One socket for public market data and, after `auth`, the account's own orders, fills and
balances.

**Request parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| id | Integer | No | Echoed on the reply, so several requests in flight can be told apart |
| action | String | Yes | `subscribe`<br>`unsubscribe`<br>`auth` |
| channel | String | No | `ticker`<br>`trade`<br>`depth`<br>`kline`<br>`depthUpdate`<br>Default `trade` |
| symbol | String | Yes | Market symbol. Not needed on `auth` |
| interval | String | Conditional | `kline` only. Bar size, see Get candles |
| grouping | String | No | `depth` only. One of the market's `depthGroupings`. Absent means the raw ladder |

**Frame parameters**

| Parameter | Type | Description |
|---|---|---|
| type | String | The channel name on a push. `subscribed`, `unsubscribed`, `authenticated` on a reply. `error` on a failure |
| id | Integer | Present on a reply to a request that carried one |
| data | Object | The payload. Absent on `error` |
| code | Integer | Error code. `error` only |
| message | String | Error text. `error` only |

Every subscription answers `subscribed`, then the current state as a first push, where the
channel has one. Money is a decimal string. Times are ISO-8601 UTC.

## Connection rules

> Close reasons

```text
1000  reconnect              24 hours reached
1008  too many connections   more than 20 from one IP
1008  message rate           over 10 messages a second for 10 seconds
1008  key inactive           the key was revoked or expired
```

| Rule | Value | On breach |
|---|---|---|
| Inbound messages per connection | 10 a second | `7009`. Closed after 10 seconds over |
| Subscriptions per connection | 50 | `7004` |
| Connections per IP | 20 | Closed with reason `too many connections` |
| Authenticated connections per key | 5 | `7010` on `auth` |
| Connection lifetime | 24 hours | Closed with code `1000`, reason `reconnect`. Reconnect, resubscribe, `auth` again |
| Send buffer | 256 KB, 5 seconds | Closed. A client that does not keep up is dropped |

The server pings every 30 seconds. Answer with a pong.

After any reconnect, read `GET /v1/orders/open` and `GET /v1/wallet/balances` again. Private
frames are not replayed.

## Authenticate

> Request

```json
{
    "id": 2,
    "action": "auth",
    "apiKey": "ak_Zm9vYmFyYmF6cXV4MTIzNDU2",
    "timestamp": "1757800000000",
    "signature": "b91d4f1a8e07c3a2d5f6e8b0a1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0"
}
```

```python
import hmac, hashlib, json, time

ts = str(int(time.time() * 1000))
signature = hmac.new(SECRET.encode(), (ts + "GET" + "/v1/ws").encode(),
                     hashlib.sha256).hexdigest()
ws.send(json.dumps({"id": 2, "action": "auth", "apiKey": API_KEY,
                    "timestamp": ts, "signature": signature}))
```

```javascript
import { createHmac } from "node:crypto";

const ts = String(Date.now());
const signature = createHmac("sha256", SECRET)
  .update(ts + "GET" + "/v1/ws")
  .digest("hex");
ws.send(JSON.stringify({ id: 2, action: "auth", apiKey: API_KEY,
                         timestamp: ts, signature }));
```

> Reply

```json
{
    "type": "authenticated",
    "id": 2,
    "data": {
        "uid": 4829301756
    }
}
```

Authenticate the connection. From then on every private frame for the account arrives on it.
There is no subscribe step for private data. The key needs read permission.

**Request parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| action | String | Yes | `auth` |
| apiKey | String | Yes | API key |
| timestamp | String | Yes | Epoch milliseconds |
| signature | String | Yes | `HMAC-SHA256(secret, timestamp + "GET" + "/v1/ws")`, lowercase hex |

**Reply parameters**

| Parameter | Type | Description |
|---|---|---|
| uid | Integer | Account id |

A failed `auth` answers an `error` frame with the REST code, `2101` to `2106`. A second `auth`
on the same connection answers `7008`.

## Ticker channel

> Request

```json
{
    "id": 3,
    "action": "subscribe",
    "channel": "ticker",
    "symbol": "BTCUSDT"
}
```

> Push

```json
{
    "type": "ticker",
    "data": {
        "symbol": "BTCUSDT",
        "lastPrice": "75502.00",
        "openPrice": "76000.00",
        "highPrice": "76050.00",
        "lowPrice": "75400.00",
        "priceChange": "-498.00",
        "priceChangePercent": "-0.65",
        "volume": "9.97",
        "quoteVolume": "757530.12",
        "tradeCount": 41,
        "windowStart": "2026-09-13T03:00:00Z",
        "windowEnd": "2026-09-14T03:00:00Z"
    }
}
```

The 24-hour summary of one market, on every trade. The first push is the current summary.
None if the market has never traded.

**Push parameters**

The ticker object, as Get ticker returns it.

## Trade channel

> Request

```json
{
    "id": 4,
    "action": "subscribe",
    "channel": "trade",
    "symbol": "BTCUSDT"
}
```

> Push

```json
{
    "type": "trade",
    "data": {
        "symbol": "BTCUSDT",
        "sequence": 18422,
        "price": "75502.00",
        "quantity": "0.01000",
        "quoteQuantity": "755.02",
        "takerSide": "SELL",
        "time": "2026-09-14T02:59:58.120Z"
    }
}
```

Every trade on one market, as it happens. No first push.

**Push parameters**

| Parameter | Type | Description |
|---|---|---|
| symbol | String | Market symbol |
| sequence | Integer | Engine sequence, per market, dense. A gap means a missed trade |
| price | String | Trade price |
| quantity | String | Base quantity |
| quoteQuantity | String | Quote quantity |
| takerSide | String | `BUY`<br>`SELL` |
| time | String | Execution time, ISO-8601 UTC |

## Depth channel

> Request

```json
{
    "id": 5,
    "action": "subscribe",
    "channel": "depth",
    "symbol": "BTCUSDT",
    "grouping": "0.1"
}
```

> Push

```json
{
    "type": "depth",
    "data": {
        "symbol": "BTCUSDT",
        "lastSequence": 18422,
        "bids": [
            {
                "price": "75502.0",
                "quantity": "1.20000"
            },
            {
                "price": "75501.9",
                "quantity": "0.03000"
            }
        ],
        "asks": [
            {
                "price": "75502.1",
                "quantity": "0.50000"
            },
            {
                "price": "75503.0",
                "quantity": "2.00000"
            }
        ]
    }
}
```

The top 50 levels a side, whole, on every change. Each frame replaces the last. The first
push is the current book, empty if nothing rests.

**Request parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| grouping | String | No | One of the market's `depthGroupings`. Absent means the raw ladder |

**Push parameters**

| Parameter | Type | Description |
|---|---|---|
| symbol | String | Market symbol |
| lastSequence | Integer | Engine sequence this book reflects |
| bids | Array | Best price first |
| > price | String | Price |
| > quantity | String | Quantity at this price |
| asks | Array | Best price first |
| > price | String | Price |
| > quantity | String | Quantity at this price |

## Kline channel

> Request

```json
{
    "id": 6,
    "action": "subscribe",
    "channel": "kline",
    "symbol": "BTCUSDT",
    "interval": "1m"
}
```

> Push

```json
{
    "type": "kline",
    "data": {
        "symbol": "BTCUSDT",
        "interval": "1m",
        "openTime": "2026-09-14T02:59:00Z",
        "closeTime": "2026-09-14T03:00:00Z",
        "open": "75500.00",
        "high": "75510.00",
        "low": "75495.00",
        "close": "75502.00",
        "volume": "0.31000",
        "quoteVolume": "23405.12",
        "tradeCount": 4,
        "closed": false
    }
}
```

The bar being drawn, whole, on every change. The first push is the current bar. None if the
market has never traded.

**Request parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| interval | String | Yes | Bar size, see Get candles |

**Push parameters**

| Parameter | Type | Description |
|---|---|---|
| symbol | String | Market symbol |
| interval | String | Bar size |
| openTime | String | Bucket open, ISO-8601 UTC |
| closeTime | String | Bucket close, ISO-8601 UTC |
| open | String | Open price |
| high | String | Highest price |
| low | String | Lowest price |
| close | String | Close price |
| volume | String | Base asset traded |
| quoteVolume | String | Quote asset traded |
| tradeCount | Integer | Trades in the bucket |
| closed | Boolean | Whether the bucket has ended. A closed bar does not change again |

## Depth update channel

> Request

```json
{
    "id": 7,
    "action": "subscribe",
    "channel": "depthUpdate",
    "symbol": "BTCUSDT"
}
```

> Push

```json
{
    "type": "depthUpdate",
    "data": {
        "symbol": "BTCUSDT",
        "firstSequence": 18423,
        "lastSequence": 18427,
        "bids": [
            [
                "75502.00",
                "0.80000"
            ],
            [
                "75490.00",
                "0"
            ]
        ],
        "asks": [
            [
                "75502.10",
                "0.50000"
            ]
        ]
    }
}
```

The full ladder, as changes. Each frame carries only the levels that changed, each with its
new size, `0` meaning removed. Frames are batched every 100 ms. Raw tick prices, no grouping.
No first push.

**Push parameters**

| Parameter | Type | Description |
|---|---|---|
| symbol | String | Market symbol |
| firstSequence | Integer | First engine sequence folded into this frame |
| lastSequence | Integer | Last engine sequence folded into this frame |
| bids | Array | Changed bid levels |
| > | Array | `[price, quantity]` |
| asks | Array | Changed ask levels |
| > | Array | `[price, quantity]` |

**Keeping a book**

1. Subscribe and buffer frames.
2. `GET /v1/markets/{symbol}/depth?limit=5000`. Note its `lastSequence` as S.
3. Drop buffered frames whose `lastSequence` is at or below S. The first frame applied must
   have `firstSequence` at or below S + 1 and `lastSequence` at or above S + 1. Otherwise
   fetch the snapshot again.
4. Apply each frame. Set the quantity at each price, remove the level at `0`. Every later
   frame must have `firstSequence` equal to the previous `lastSequence` + 1. On any gap, go
   back to step 2.

## Order update

> Push, accepted

```json
{
    "type": "orderUpdate",
    "data": {
        "orderId": "0192b3c4-5d6e-7f80-9a1b-2c3d4e5f6071",
        "symbol": "BTCUSDT",
        "status": "NEW",
        "executedQuantity": null,
        "remainingQuantity": null,
        "lastFillPrice": null,
        "lastFillQuantity": null,
        "rejectReason": null,
        "cancelReason": null,
        "clientOrderId": "bot-1-000123",
        "time": "2026-09-14T03:00:00.412Z",
        "order": {
            "orderId": "0192b3c4-5d6e-7f80-9a1b-2c3d4e5f6071",
            "clientOrderId": "bot-1-000123",
            "symbol": "BTCUSDT",
            "side": "BUY",
            "orderType": "LIMIT",
            "timeInForce": "GTC",
            "status": "NEW",
            "price": "75000.00",
            "stopPrice": null,
            "trailingDeltaBps": null,
            "triggerDirection": null,
            "quantity": "0.01000",
            "quoteOrderQty": null,
            "filledQuantity": "0",
            "remainingQuantity": "0.01000",
            "cumulativeQuoteQuantity": "0",
            "averageFillPrice": "0",
            "rejectReason": null,
            "createdAt": "2026-09-14T03:00:00.412Z",
            "updatedAt": "2026-09-14T03:00:00.412Z",
            "amendPending": false
        }
    }
}
```

> Push, filled

```json
{
    "type": "orderUpdate",
    "data": {
        "orderId": "0192b3c4-5d6e-7f80-9a1b-2c3d4e5f6071",
        "symbol": "BTCUSDT",
        "status": "PARTIALLY_FILLED",
        "executedQuantity": null,
        "remainingQuantity": "0.00600",
        "lastFillPrice": "75000.00",
        "lastFillQuantity": "0.00400",
        "rejectReason": null,
        "cancelReason": null,
        "clientOrderId": null,
        "time": "2026-09-14T03:00:02.918Z",
        "order": null
    }
}
```

> Push, canceled

```json
{
    "type": "orderUpdate",
    "data": {
        "orderId": "0192b3c4-5d6e-7f80-9a1b-2c3d4e5f6071",
        "symbol": "BTCUSDT",
        "status": "CANCELED",
        "executedQuantity": "0.00400",
        "remainingQuantity": "0.00600",
        "lastFillPrice": null,
        "lastFillQuantity": null,
        "rejectReason": null,
        "cancelReason": "CANCEL_REASON_USER_REQUESTED",
        "clientOrderId": "bot-1-000123",
        "time": "2026-09-14T03:00:09.001Z",
        "order": null,
        "amend": null
    }
}
```

> Push, after an amend was applied

```json
{
    "type": "orderUpdate",
    "data": {
        "orderId": "0192b3c4-5d6e-7f80-9a1b-2c3d4e5f6071",
        "symbol": "BTCUSDT",
        "status": "PARTIALLY_FILLED",
        "executedQuantity": "0.00400",
        "remainingQuantity": "0.01600",
        "lastFillPrice": null,
        "lastFillQuantity": null,
        "rejectReason": null,
        "cancelReason": null,
        "clientOrderId": "bot-1-000123",
        "time": "2026-09-14T03:00:11.204Z",
        "order": null,
        "amend": {
            "rejectReason": null,
            "price": "75100",
            "stopPrice": null,
            "quantity": "0.02"
        }
    }
}
```

Private. One change to one of the account's orders. Sent on acceptance, every fill, rejection,
cancellation, and the outcome of every amend.

**Push parameters**

| Parameter | Type | Description |
|---|---|---|
| orderId | String | Order id |
| symbol | String | Market symbol |
| status | String | `NEW`<br>`PENDING_TRIGGER`<br>`PARTIALLY_FILLED`<br>`FILLED`<br>`CANCELED`<br>`REJECTED`<br>`null` on a refused amend |
| executedQuantity | String | Base quantity filled over the order's life. On `CANCELED` and on an applied amend |
| remainingQuantity | String | Base quantity left. On a fill, on `CANCELED` and on an applied amend |
| lastFillPrice | String | Price of this fill. On a fill |
| lastFillQuantity | String | Base quantity of this fill. On a fill |
| rejectReason | String | `REJECTED` only<br>`REJECT_REASON_WOULD_TAKE`<br>`REJECT_REASON_INSUFFICIENT_LIQUIDITY`<br>`REJECT_REASON_SELF_TRADE`<br>`REJECT_REASON_DUPLICATE_ORDER_ID`<br>`REJECT_REASON_UNKNOWN_SYMBOL`<br>`REJECT_REASON_MARKET_NOT_TRADING` |
| cancelReason | String | `CANCELED` only<br>`CANCEL_REASON_USER_REQUESTED`<br>`CANCEL_REASON_IOC_REMAINDER`<br>`CANCEL_REASON_EXPIRED`<br>`CANCEL_REASON_MARKET_CLOSED`<br>`CANCEL_REASON_ADMIN` |
| clientOrderId | String | Client id. `null` on a fill |
| time | String | Engine time, ISO-8601 UTC |
| order | Object | The whole order, as Get order returns it. Only on the acceptance of a limit order that rested untouched or a conditional order that parked. `null` otherwise |
| amend | Object | The outcome of an amend. `null` on every other frame |
| > rejectReason | String | `null` when the amend was applied. Otherwise the order is unchanged, and `status` is `null`<br>`AMEND_REJECT_REASON_ORDER_NOT_FOUND`<br>`AMEND_REJECT_REASON_QUANTITY_NOT_ABOVE_FILLED`<br>`AMEND_REJECT_REASON_WOULD_TAKE`<br>`AMEND_REJECT_REASON_WOULD_TRIGGER_IMMEDIATELY`<br>`AMEND_REJECT_REASON_FIELD_NOT_AMENDABLE` |
| > price | String | Limit price after the amend. `null` when the order has none, or on a refusal |
| > stopPrice | String | Trigger price after the amend. `null` when the order has none, or on a refusal |
| > quantity | String | Total size after the amend, in quote on a quote-sized order. `null` on a refusal |

## Fill

> Push

```json
{
    "type": "fill",
    "data": {
        "tradeId": "0192b3c8-1a2b-7c3d-8e4f-5a6b7c8d9e0f",
        "orderId": "0192b3c4-5d6e-7f80-9a1b-2c3d4e5f6071",
        "symbol": "BTCUSDT",
        "price": "75000.00",
        "quantity": "0.00400",
        "quoteQuantity": "300.00",
        "side": "BUY",
        "isMaker": true,
        "fee": "0.00000400",
        "feeAsset": "BTC"
    }
}
```

Private. One of the account's fills, once settled, with the fee charged. Arrives after the
`orderUpdate` for the same fill.

**Push parameters**

| Parameter | Type | Description |
|---|---|---|
| tradeId | String | Trade id |
| orderId | String | The account's order |
| symbol | String | Market symbol |
| price | String | Trade price |
| quantity | String | Base quantity |
| quoteQuantity | String | Quote quantity |
| side | String | `BUY`<br>`SELL` |
| isMaker | Boolean | Whether the account's order was resting |
| fee | String | Fee charged. Negative is a rebate |
| feeAsset | String | Asset the fee was charged in |

## Balance changed

> Push

```json
{
    "type": "balanceChanged",
    "data": {
        "asset": "USDT",
        "free": "999245.12",
        "locked": "755.02",
        "version": 119
    }
}
```

Private. One asset's balance after a change. An asset not in the frame is unchanged. Frames may
arrive out of order. Apply one only when its `version` is higher than the one held for that
asset, starting from `GET /v1/wallet/balances`.

**Push parameters**

| Parameter | Type | Description |
|---|---|---|
| asset | String | Asset symbol |
| free | String | Free to trade |
| locked | String | Held by open orders |
| version | Integer | Row version for this asset |
