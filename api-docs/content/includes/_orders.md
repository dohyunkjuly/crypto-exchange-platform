# Orders

Signed endpoints. Placing, amending and cancelling need trade permission, reading needs read
permission. Placing and amending also need `canTrade` on the account. Cancelling and reading
never do.

Placing, amending and cancelling answer `202`. The command is durably recorded at that point --
it reaches the engine within milliseconds normally, and is retried until it does if the queue
is briefly unavailable -- and the engine decides the outcome. The result arrives on the WebSocket `orderUpdate` channel, or by reading
the order back.

## Place order

> Request

```shell
# Place order
POST /v1/orders

{
    "symbol": "BTCUSDT",
    "side": "BUY",
    "orderType": "LIMIT",
    "timeInForce": "GTC",
    "price": "75000.00",
    "quantity": "0.01000",
    "clientOrderId": "bot-1-000123"
}
```

```python
print(request("POST", "/v1/orders", {
    "symbol": "BTCUSDT",
    "side": "BUY",
    "orderType": "LIMIT",
    "timeInForce": "GTC",
    "price": "75000.00",
    "quantity": "0.01000",
    "clientOrderId": "bot-1-000123",
}))
```

```javascript
console.log(await request("POST", "/v1/orders", {
  symbol: "BTCUSDT",
  side: "BUY",
  orderType: "LIMIT",
  timeInForce: "GTC",
  price: "75000.00",
  quantity: "0.01000",
  clientOrderId: "bot-1-000123",
}));
```

> Market buy, sized in quote

```json
{
    "symbol": "BTCUSDT",
    "side": "BUY",
    "orderType": "MARKET",
    "quoteOrderQty": "100.00"
}
```

> Stop-limit sell, trailing

```json
{
    "symbol": "BTCUSDT",
    "side": "SELL",
    "orderType": "STOP_LOSS_LIMIT",
    "timeInForce": "GTC",
    "stopPrice": "74000.00",
    "price": "73900.00",
    "quantity": "0.01000",
    "trailingDeltaBps": 50
}
```

> Response

```json
{
    "status": 202,
    "code": 0,
    "data": {
        "orderId": "0192b3c4-5d6e-7f80-9a1b-2c3d4e5f6071",
        "clientOrderId": "bot-1-000123",
        "symbol": "BTCUSDT",
        "side": "BUY",
        "orderType": "LIMIT",
        "status": "NEW",
        "transactTime": "2026-09-14T03:00:00.412Z"
    },
    "message": "Accepted"
}
```

Place an order. Funds are locked and the order is queued before the response.

**Rate limit:** weight 1. Counts toward the order limits

**HTTP request**

`POST /v1/orders`

**Request parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| symbol | String | Yes | Market symbol |
| side | String | Yes | `BUY`<br>`SELL` |
| orderType | String | Yes | `LIMIT`<br>`MARKET`<br>`LIMIT_MAKER`<br>`STOP_LOSS`<br>`STOP_LOSS_LIMIT`<br>`TAKE_PROFIT`<br>`TAKE_PROFIT_LIMIT` |
| timeInForce | String | Conditional | `GTC`<br>`IOC`<br>`FOK`<br>Required on every type but `MARKET`, which is always `IOC`. `FOK` cannot be combined with `EXPIRE_BOTH` |
| price | String | Conditional | Limit price, on the price tick. Required on `LIMIT`, `LIMIT_MAKER`, `STOP_LOSS_LIMIT`, `TAKE_PROFIT_LIMIT` |
| stopPrice | String | Conditional | Trigger price, on the price tick. Required on the four conditional types unless `trailingDeltaBps` is set, when it becomes the activation price |
| quantity | String | Conditional | Base quantity, on the quantity step. Required on every order except a market-firing BUY, where it is refused |
| quoteOrderQty | String | Conditional | Quote amount to spend. Required on a market-firing BUY, refused everywhere else |
| trailingDeltaBps | Integer | No | Conditional types only. Basis points the market must reverse from its extreme before firing, `10` to `2000` |
| clientOrderId | String | No | Client id. Replaying one returns the original order instead of placing a new one |
| selfTradePreventionMode | String | No | `EXPIRE_MAKER`<br>`EXPIRE_TAKER`<br>`EXPIRE_BOTH`<br>Default `EXPIRE_MAKER`. `EXPIRE_BOTH` cannot be combined with `FOK` |

Market-firing: `MARKET`, `STOP_LOSS`, `TAKE_PROFIT`. Each fires as a market order.

| Type | Side | Fires when the last price |
|---|---|---|
| STOP_LOSS, STOP_LOSS_LIMIT | SELL | falls to or below `stopPrice` |
| STOP_LOSS, STOP_LOSS_LIMIT | BUY | rises to or above `stopPrice` |
| TAKE_PROFIT, TAKE_PROFIT_LIMIT | SELL | rises to or above `stopPrice` |
| TAKE_PROFIT, TAKE_PROFIT_LIMIT | BUY | falls to or below `stopPrice` |

A conditional order whose condition already holds at placement is accepted, then rejected by the engine with `rejectReason` set.

**Response parameters**

| Parameter | Type | Description |
|---|---|---|
| orderId | String | Order id |
| clientOrderId | String | Client id as sent. `null` when none |
| symbol | String | Market symbol |
| side | String | `BUY`<br>`SELL` |
| orderType | String | Order type |
| status | String | `NEW`, or `PENDING_TRIGGER` on a conditional order. A replayed `clientOrderId` shows the original order's current status |
| transactTime | String | Acceptance time, ISO-8601 UTC |

## Get order

> Request

```shell
# Get order
GET /v1/orders/0192b3c4-5d6e-7f80-9a1b-2c3d4e5f6071
```

```python
print(request("GET", "/v1/orders/0192b3c4-5d6e-7f80-9a1b-2c3d4e5f6071"))
```

```javascript
console.log(await request("GET", "/v1/orders/0192b3c4-5d6e-7f80-9a1b-2c3d4e5f6071"));
```

> Response

```json
{
    "status": 200,
    "code": 0,
    "data": {
        "orderId": "0192b3c4-5d6e-7f80-9a1b-2c3d4e5f6071",
        "clientOrderId": "bot-1-000123",
        "symbol": "BTCUSDT",
        "side": "BUY",
        "orderType": "LIMIT",
        "timeInForce": "GTC",
        "status": "PARTIALLY_FILLED",
        "price": "75000.00",
        "stopPrice": null,
        "trailingDeltaBps": null,
        "triggerDirection": null,
        "quantity": "0.01000",
        "quoteOrderQty": null,
        "filledQuantity": "0.00400",
        "remainingQuantity": "0.00600",
        "cumulativeQuoteQuantity": "300.00",
        "averageFillPrice": "75000.00",
        "rejectReason": null,
        "createdAt": "2026-09-14T03:00:00.412Z",
        "updatedAt": "2026-09-14T03:00:02.918Z",
        "amendPending": false
    },
    "message": "OK"
}
```

Retrieve one order, open or closed.

**Rate limit:** weight 2

**HTTP request**

`GET /v1/orders/{orderId}`

**Request parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| orderId | String | Yes | Order id |

**Response parameters**

| Parameter | Type | Description |
|---|---|---|
| orderId | String | Order id |
| clientOrderId | String | Client id. `null` when none |
| symbol | String | Market symbol |
| side | String | `BUY`<br>`SELL` |
| orderType | String | Order type |
| timeInForce | String | `GTC`<br>`IOC`<br>`FOK` |
| status | String | `PENDING_TRIGGER`<br>`NEW`<br>`PARTIALLY_FILLED`<br>`FILLED`<br>`PENDING_CANCEL`<br>`CANCELED`<br>`REJECTED`<br>`EXPIRED` |
| price | String | Limit price. `null` on a market-firing order |
| stopPrice | String | Trigger price. `null` unless conditional |
| trailingDeltaBps | Integer | Trailing delta. `null` unless trailing |
| triggerDirection | String | `RISE`<br>`FALL`<br>`null` unless conditional |
| quantity | String | Base quantity. `null` on a quote-sized order |
| quoteOrderQty | String | Quote amount. `null` on a base-sized order |
| filledQuantity | String | Base quantity filled so far |
| remainingQuantity | String | `quantity − filledQuantity`. `null` on a quote-sized order |
| cumulativeQuoteQuantity | String | Quote amount filled so far |
| averageFillPrice | String | `cumulativeQuoteQuantity ÷ filledQuantity`. `0` before the first fill |
| rejectReason | String | Engine's reason. `null` unless `REJECTED` |
| createdAt | String | Placement time, ISO-8601 UTC |
| updatedAt | String | Last change, ISO-8601 UTC |
| amendPending | Boolean | An amend is queued and not yet applied. A second amend is refused with `5026` until it is |

## Get open orders

> Request

```shell
# Get open orders
GET /v1/orders/open?symbol=BTCUSDT
```

```python
print(request("GET", "/v1/orders/open?symbol=BTCUSDT"))
```

```javascript
console.log(await request("GET", "/v1/orders/open?symbol=BTCUSDT"));
```

> Response

```json
{
    "status": 200,
    "code": 0,
    "data": [
        {
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
    ],
    "message": "OK"
}
```

Retrieve every order that can still fill, newest first. Includes `PENDING_TRIGGER` and
`PENDING_CANCEL`.

**Rate limit:** weight 3

**HTTP request**

`GET /v1/orders/open`

**Request parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| symbol | String | No | Market symbol. Absent means every market |

**Response parameters**

Array of the order object above.

## Get order history

> Request

```shell
# Get order history
GET /v1/orders?symbol=BTCUSDT&startTime=2026-09-01T00:00:00Z&endTime=2026-09-15T00:00:00Z&page=0&size=100
```

```python
print(request("GET", "/v1/orders?symbol=BTCUSDT"
              "&startTime=2026-09-01T00:00:00Z&endTime=2026-09-15T00:00:00Z&page=0&size=100"))
```

```javascript
console.log(await request("GET", "/v1/orders?symbol=BTCUSDT"
  + "&startTime=2026-09-01T00:00:00Z&endTime=2026-09-15T00:00:00Z&page=0&size=100"));
```

> Response

```json
{
    "status": 200,
    "code": 0,
    "data": [
        {
            "orderId": "0192b3c4-5d6e-7f80-9a1b-2c3d4e5f6071",
            "clientOrderId": "bot-1-000123",
            "symbol": "BTCUSDT",
            "side": "BUY",
            "orderType": "LIMIT",
            "timeInForce": "GTC",
            "status": "FILLED",
            "price": "75000.00",
            "stopPrice": null,
            "trailingDeltaBps": null,
            "triggerDirection": null,
            "quantity": "0.01000",
            "quoteOrderQty": null,
            "filledQuantity": "0.01000",
            "remainingQuantity": "0",
            "cumulativeQuoteQuantity": "750.00",
            "averageFillPrice": "75000.00",
            "rejectReason": null,
            "createdAt": "2026-09-14T03:00:00.412Z",
            "updatedAt": "2026-09-14T03:00:05.104Z",
            "amendPending": false
        }
    ],
    "message": "OK"
}
```

Retrieve orders, open and closed, newest first.

**Rate limit:** weight 10

**HTTP request**

`GET /v1/orders`

**Request parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| symbol | String | No | Market symbol |
| side | String | No | `BUY`<br>`SELL` |
| startTime | String | No | Window start, ISO-8601 UTC. Default 7 days before `endTime` |
| endTime | String | No | Window end, ISO-8601 UTC. Default now |
| timeField | String | No | Which time the window applies to<br>`CREATED_AT`, default<br>`UPDATED_AT` |
| page | Integer | No | Page number from `0` |
| size | Integer | No | Page size. Default `100`, maximum `500` |

The window may span at most 90 days, else `5017`.

**Response parameters**

Array of the order object above.

## Amend order

> Request

```shell
# Amend order
PATCH /v1/orders/0192b3c4-5d6e-7f80-9a1b-2c3d4e5f6071
body
{
    "price": "75100.00",
    "quantity": "0.02000"
}
```

```python
print(request("PATCH", "/v1/orders/0192b3c4-5d6e-7f80-9a1b-2c3d4e5f6071",
              {"price": "75100.00", "quantity": "0.02000"}))
```

```javascript
console.log(await request("PATCH", "/v1/orders/0192b3c4-5d6e-7f80-9a1b-2c3d4e5f6071",
    { price: "75100.00", quantity: "0.02000" }));
```

> Response

```json
{
    "status": 202,
    "code": 0,
    "data": {
        "orderId": "0192b3c4-5d6e-7f80-9a1b-2c3d4e5f6071",
        "status": "AMENDING"
    },
    "message": "Accepted"
}
```

Change an open order in place. Send only the fields to change.

The outcome arrives as an `orderUpdate` frame with `amend` set: the new terms, or the reason the
engine refused and left the order as it was. Until then the order reads `amendPending: true` and
a second amend is refused with `5026`. Never retry a timed-out amend: read the order back.

An amend can trade, so a market that is not open for trading refuses it with `5002`, and a
market with no engine running refuses it with `5031`, as each refuses a new order. Cancelling
is never refused.

If the new terms need more funds locked, they are locked before the `202`, and `4001` is
returned if the balance is short. Funds no longer needed are released once the amend is applied.

| Order | Can change | Notes |
|---|---|---|
| Resting `LIMIT` | `price`, `quantity` | A price that crosses the book trades at once, as a taker |
| Resting `LIMIT_MAKER` | `price`, `quantity` | A price that would cross is refused: `AMEND_REJECT_REASON_WOULD_TAKE` |
| Waiting conditional order | `stopPrice`, `price` on the `_LIMIT` types, size | A `stopPrice` that already holds is refused: `AMEND_REJECT_REASON_WOULD_TRIGGER_IMMEDIATELY` |
| Waiting trailing order | size only | Cancel and place again to change the trigger |
| `MARKET`, `IOC`, `FOK`, or closed | nothing | `5025` |

Size is `quantity`, or `quoteOrderQty` on an order that was placed with one. A conditional
`_LIMIT` order that has fired is a resting `LIMIT`.

**Queue priority.** A smaller `quantity` at the same price keeps the order's place in the queue.
A new price, or a larger `quantity`, moves it to the back.

**Rate limit:** weight 1. Counts as an order

**HTTP request**

`PATCH /v1/orders/{orderId}`

`PATCH /v1/orders/by-client-id/{clientOrderId}`

**Request parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| orderId | String | Yes | Order id, or `clientOrderId` on the second path |
| price | String | No | New limit price, on the price tick |
| stopPrice | String | No | New trigger price, on the price tick |
| quantity | String | No | New **total** base quantity, filled part included. Must be above `filledQuantity` |
| quoteOrderQty | String | No | New total quote amount, on a quote-sized order |

**Response parameters**

| Parameter | Type | Description |
|---|---|---|
| orderId | String | The id the request used |
| status | String | Always `AMENDING` |

## Cancel order

> Request

```shell
# Cancel order
DELETE /v1/orders/0192b3c4-5d6e-7f80-9a1b-2c3d4e5f6071
```

```python
print(request("DELETE", "/v1/orders/0192b3c4-5d6e-7f80-9a1b-2c3d4e5f6071"))
```

```javascript
console.log(await request("DELETE", "/v1/orders/0192b3c4-5d6e-7f80-9a1b-2c3d4e5f6071"));
```

> Response

```json
{
    "status": 202,
    "code": 0,
    "data": {
        "orderId": "0192b3c4-5d6e-7f80-9a1b-2c3d4e5f6071",
        "status": "CANCELING"
    },
    "message": "Accepted"
}
```

Cancel one open order.

**Rate limit:** weight 1

**HTTP request**

`DELETE /v1/orders/{orderId}`

**Request parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| orderId | String | Yes | Order id |

**Response parameters**

| Parameter | Type | Description |
|---|---|---|
| orderId | String | Order id |
| status | String | Always `CANCELING`. The order reads `CANCELED` once the engine has applied it |

## Cancel order by client id

> Request

```shell
# Cancel order by client id
DELETE /v1/orders/by-client-id/bot-1-000123
```

```python
print(request("DELETE", "/v1/orders/by-client-id/bot-1-000123"))
```

```javascript
console.log(await request("DELETE", "/v1/orders/by-client-id/bot-1-000123"));
```

> Response

```json
{
    "status": 202,
    "code": 0,
    "data": {
        "orderId": "bot-1-000123",
        "status": "CANCELING"
    },
    "message": "Accepted"
}
```

Cancel one open order by the id the client gave it.

**Rate limit:** weight 1

**HTTP request**

`DELETE /v1/orders/by-client-id/{clientOrderId}`

**Request parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| clientOrderId | String | Yes | Client id |

**Response parameters**

| Parameter | Type | Description |
|---|---|---|
| orderId | String | The client id as sent |
| status | String | Always `CANCELING` |

## Cancel all open orders

> Request

```shell
# Cancel all open orders
DELETE /v1/orders/open?symbol=BTCUSDT&scope=BOOK
```

```python
print(request("DELETE", "/v1/orders/open?symbol=BTCUSDT&scope=BOOK"))
```

```javascript
console.log(await request("DELETE", "/v1/orders/open?symbol=BTCUSDT&scope=BOOK"));
```

> Response

```json
{
    "status": 202,
    "code": 0,
    "data": {
        "count": 3
    },
    "message": "Accepted"
}
```

Cancel every open order matching the filters.

**Rate limit:** weight 1

**HTTP request**

`DELETE /v1/orders/open`

**Request parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| symbol | String | No | Market symbol. Absent means every market |
| side | String | No | `BUY`<br>`SELL` |
| type | String | No | Order type |
| scope | String | No | `ALL`, default<br>`BOOK`: resting orders only<br>`TRIGGERS`: untriggered conditional orders only |

**Response parameters**

| Parameter | Type | Description |
|---|---|---|
| count | Integer | Orders open and matching when the request was read. The engine may cancel more |
