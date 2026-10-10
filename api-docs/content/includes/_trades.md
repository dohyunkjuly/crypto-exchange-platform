# Trades

## Get my trades

> Request

```shell
# Get my trades
GET /v1/trades?symbol=BTCUSDT&page=0&size=100
```

```python
print(request("GET", "/v1/trades?symbol=BTCUSDT&page=0&size=100"))
```

```javascript
console.log(await request("GET", "/v1/trades?symbol=BTCUSDT&page=0&size=100"));
```

> Response

```json
{
    "status": 200,
    "code": 0,
    "data": [
        {
            "tradeId": "0192b3c8-1a2b-7c3d-8e4f-5a6b7c8d9e0f",
            "symbol": "BTCUSDT",
            "orderId": "0192b3c4-5d6e-7f80-9a1b-2c3d4e5f6071",
            "orderType": "LIMIT",
            "price": "75000.00",
            "quantity": "0.00400",
            "quoteQuantity": "300.00",
            "side": "BUY",
            "isMaker": true,
            "fee": "0.00000400",
            "feeAsset": "BTC",
            "time": "2026-09-14T03:00:02.918Z"
        }
    ],
    "message": "OK"
}
```

Retrieve the account's fills, newest first. The key needs read permission.

**Rate limit:** weight 10

**HTTP request**

`GET /v1/trades`

**Request parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| symbol | String | No | Market symbol |
| side | String | No | `BUY`<br>`SELL` |
| startTime | String | No | Window start, ISO-8601 UTC. Default 7 days before `endTime` |
| endTime | String | No | Window end, ISO-8601 UTC. Default now |
| page | Integer | No | Page number from `0` |
| size | Integer | No | Page size. Default `100`, maximum `500` |

The window may span at most 90 days, else `5017`.

**Response parameters**

| Parameter | Type | Description |
|---|---|---|
| tradeId | String | Trade id |
| symbol | String | Market symbol |
| orderId | String | The account's order in this trade |
| orderType | String | That order's type |
| price | String | Trade price |
| quantity | String | Base quantity |
| quoteQuantity | String | Quote quantity |
| side | String | The account's side<br>`BUY`<br>`SELL` |
| isMaker | Boolean | Whether the account's order was resting |
| fee | String | Fee charged. Negative is a rebate |
| feeAsset | String | Asset the fee was charged in. Base on a buy, quote on a sell |
| time | String | Execution time, ISO-8601 UTC |
