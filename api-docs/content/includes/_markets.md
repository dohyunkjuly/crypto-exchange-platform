# Markets

Public endpoints. No signature required.

## Get server time

> Request

```shell
# Get server time
GET /v1/time
```

```python
import json, urllib.request
print(json.load(urllib.request.urlopen("https://api.example.com/v1/time")))
```

```javascript
const res = await fetch("https://api.example.com/v1/time");
console.log(await res.json());
```

> Response

```json
{
    "status": 200,
    "code": 0,
    "data": {
        "serverTime": "2026-09-14T03:00:00.412Z"
    },
    "message": "OK"
}
```

Retrieve the server time.

**Rate limit:** weight 1

**HTTP request**

`GET /v1/time`

**Response parameters**

| Parameter | Type | Description |
|---|---|---|
| serverTime | String | Server time, ISO-8601 UTC |

## Get markets

> Request

```shell
# Get markets
GET /v1/markets/info
```

```python
print(json.load(urllib.request.urlopen("https://api.example.com/v1/markets/info")))
```

```javascript
const res = await fetch("https://api.example.com/v1/markets/info");
console.log(await res.json());
```

> Response

```json
{
    "status": 200,
    "code": 0,
    "data": {
        "symbols": [
            {
                "symbol": "BTCUSDT",
                "baseAsset": "BTC",
                "quoteAsset": "USDT",
                "status": "TRADING",
                "priceTick": "0.01",
                "qtyStep": "0.00001",
                "minQty": "0.00001",
                "maxQty": "9000",
                "minNotional": "10",
                "maxNotional": "10000000",
                "marketMinQty": "0.0002",
                "marketMaxQty": "100",
                "minQuoteQty": "10",
                "maxQuoteQty": "10000000",
                "baseDecimals": 5,
                "quoteDecimals": 2,
                "marketOrdersEnabled": true,
                "stopOrdersEnabled": true,
                "depthGroupings": [
                    "0.01",
                    "0.1",
                    "1",
                    "10",
                    "100"
                ],
                "paused": false
            }
        ],
        "rateLimits": [
            {
                "type": "REQUEST_WEIGHT",
                "window": "1m",
                "limit": 1200
            },
            {
                "type": "ORDERS",
                "window": "10s",
                "limit": 100
            },
            {
                "type": "ORDERS",
                "window": "1d",
                "limit": 1000
            }
        ]
    },
    "message": "OK"
}
```

Retrieve every listed market with its order rules, and the rate limits.

**Rate limit:** weight 10

**HTTP request**

`GET /v1/markets/info`

**Response parameters**

| Parameter | Type | Description |
|---|---|---|
| symbols | Array | Listed markets |
| > symbol | String | Market symbol, e.g. `BTCUSDT` |
| > baseAsset | String | Base asset, e.g. `BTC` |
| > quoteAsset | String | Quote asset, e.g. `USDT` |
| > status | String | Market status<br>`TRADING`<br>`HALTED`<br>`CANCEL_ONLY`<br>`POST_TRADING` |
| > priceTick | String | Price increment |
| > qtyStep | String | Quantity increment |
| > minQty | String | Minimum `quantity`, limit-family orders |
| > maxQty | String | Maximum `quantity`, limit-family orders |
| > minNotional | String | Minimum `price × quantity`, limit-family orders |
| > maxNotional | String | Maximum `price × quantity`, limit-family orders |
| > marketMinQty | String | Minimum `quantity`, market-firing SELL. Absent when `minQty` applies |
| > marketMaxQty | String | Maximum `quantity`, market-firing SELL. Absent when `maxQty` applies |
| > minQuoteQty | String | Minimum `quoteOrderQty`, market-firing BUY. Absent when unbounded |
| > maxQuoteQty | String | Maximum `quoteOrderQty`, market-firing BUY. Absent when unbounded |
| > baseDecimals | Integer | Display decimals of the base asset |
| > quoteDecimals | Integer | Display decimals of the quote asset |
| > marketOrdersEnabled | Boolean | Whether market orders are accepted |
| > stopOrdersEnabled | Boolean | Whether conditional orders are accepted |
| > depthGroupings | Array | Price groupings accepted by the order book endpoint and channel |
| > paused | Boolean | No matching engine is running this market right now. New orders and amends are refused with `5031`; cancels go through. Clears on its own, usually within seconds |
| rateLimits | Array | Limits in force |
| > type | String | `REQUEST_WEIGHT`<br>`ORDERS` |
| > window | String | `1m`<br>`10s`<br>`1d` |
| > limit | Integer | Maximum in the window |

## Get ticker

> Request

```shell
# Get ticker
GET /v1/markets/BTCUSDT/ticker
```

```python
print(json.load(urllib.request.urlopen("https://api.example.com/v1/markets/BTCUSDT/ticker")))
```

```javascript
const res = await fetch("https://api.example.com/v1/markets/BTCUSDT/ticker");
console.log(await res.json());
```

> Response

```json
{
    "status": 200,
    "code": 0,
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
    },
    "message": "OK"
}
```

Retrieve the 24-hour summary of one market.

**Rate limit:** weight 1

**HTTP request**

`GET /v1/markets/{symbol}/ticker`

**Request parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| symbol | String | Yes | Market symbol, e.g. `BTCUSDT` |

**Response parameters**

| Parameter | Type | Description |
|---|---|---|
| symbol | String | Market symbol |
| lastPrice | String | Last traded price |
| openPrice | String | Price 24 hours ago |
| highPrice | String | Highest price in the window |
| lowPrice | String | Lowest price in the window |
| priceChange | String | `lastPrice − openPrice` |
| priceChangePercent | String | Change in percent, two decimals |
| volume | String | Base asset traded in the window |
| quoteVolume | String | Quote asset traded in the window |
| tradeCount | Integer | Trades in the window |
| windowStart | String | Window start, ISO-8601 UTC |
| windowEnd | String | Window end, ISO-8601 UTC |

## Get tickers

> Request

```shell
# Get tickers
GET /v1/markets/tickers
```

```python
print(json.load(urllib.request.urlopen("https://api.example.com/v1/markets/tickers")))
```

```javascript
const res = await fetch("https://api.example.com/v1/markets/tickers");
console.log(await res.json());
```

> Response

```json
{
    "status": 200,
    "code": 0,
    "data": [
        {
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
    ],
    "message": "OK"
}
```

Retrieve the 24-hour summary of every market, busiest first by quote volume.

**Rate limit:** weight 20

**HTTP request**

`GET /v1/markets/tickers`

**Response parameters**

Array of the ticker object above.

## Get order book

> Request

```shell
# Get order book
GET /v1/markets/BTCUSDT/depth?limit=50&grouping=0.1
```

```python
print(json.load(urllib.request.urlopen(
    "https://api.example.com/v1/markets/BTCUSDT/depth?limit=50&grouping=0.1")))
```

```javascript
const res = await fetch("https://api.example.com/v1/markets/BTCUSDT/depth?limit=50&grouping=0.1");
console.log(await res.json());
```

> Response

```json
{
    "status": 200,
    "code": 0,
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
    },
    "message": "OK"
}
```

Retrieve the order book of one market.

**Rate limit:** weight 1 per 100 levels requested

**HTTP request**

`GET /v1/markets/{symbol}/depth`

**Request parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| symbol | String | Yes | Market symbol |
| limit | Integer | No | Levels per side. Default `50`, maximum `5000` |
| grouping | String | No | Price grouping, one of the market's `depthGroupings`. Absent means the raw ladder |

**Response parameters**

| Parameter | Type | Description |
|---|---|---|
| symbol | String | Market symbol |
| lastSequence | Integer | Event sequence this book reflects |
| bids | Array | Bids, best price first |
| > price | String | Price |
| > quantity | String | Quantity at this price |
| asks | Array | Asks, best price first |
| > price | String | Price |
| > quantity | String | Quantity at this price |

## Get trades

> Request

```shell
# Get trades
GET /v1/markets/BTCUSDT/trades?limit=50
```

```python
print(json.load(urllib.request.urlopen("https://api.example.com/v1/markets/BTCUSDT/trades?limit=50")))
```

```javascript
const res = await fetch("https://api.example.com/v1/markets/BTCUSDT/trades?limit=50");
console.log(await res.json());
```

> Response

```json
{
    "status": 200,
    "code": 0,
    "data": [
        {
            "sequence": 18422,
            "price": "75502.00",
            "quantity": "0.01000",
            "quoteQuantity": "755.02",
            "takerSide": "SELL",
            "time": "2026-09-14T02:59:58.120Z"
        }
    ],
    "message": "OK"
}
```

Retrieve the most recent trades of one market, newest first.

**Rate limit:** weight 1

**HTTP request**

`GET /v1/markets/{symbol}/trades`

**Request parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| symbol | String | Yes | Market symbol |
| limit | Integer | No | Default `50`, maximum `500` |

**Response parameters**

| Parameter | Type | Description |
|---|---|---|
| sequence | Integer | Trade sequence, per market |
| price | String | Trade price |
| quantity | String | Base quantity |
| quoteQuantity | String | Quote quantity |
| takerSide | String | Taker side<br>`BUY`<br>`SELL` |
| time | String | Trade time, ISO-8601 UTC |

## Get candles

> Request

```shell
# Get candles
GET /v1/markets/BTCUSDT/candles?interval=1m&limit=500
```

```python
print(json.load(urllib.request.urlopen(
    "https://api.example.com/v1/markets/BTCUSDT/candles?interval=1m&limit=500")))
```

```javascript
const res = await fetch("https://api.example.com/v1/markets/BTCUSDT/candles?interval=1m&limit=500");
console.log(await res.json());
```

> Response

```json
{
    "status": 200,
    "code": 0,
    "data": [
        {
            "openTime": "2026-09-14T02:58:00Z",
            "open": "75500.00",
            "high": "75510.00",
            "low": "75495.00",
            "close": "75502.00",
            "volume": "0.31000",
            "quoteVolume": "23405.12",
            "tradeCount": 4
        }
    ],
    "message": "OK"
}
```

Retrieve candles of one market, oldest first.

**Rate limit:** weight 1

**HTTP request**

`GET /v1/markets/{symbol}/candles`

**Request parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| symbol | String | Yes | Market symbol |
| interval | String | No | Bar size. Default `1m`<br>`1m` `5m` `15m` `30m`<br>`1h` `2h` `4h` `6h` `8h` `12h`<br>`1d` `1M` |
| limit | Integer | No | Default `500`, maximum `1000` |
| endTime | String | No | Newest bucket wanted, ISO-8601 UTC. Default now. To page back, pass one millisecond before the oldest bucket held |

**Response parameters**

| Parameter | Type | Description |
|---|---|---|
| openTime | String | Bucket open time, ISO-8601 UTC |
| open | String | Open price |
| high | String | Highest price |
| low | String | Lowest price |
| close | String | Close price |
| volume | String | Base asset traded |
| quoteVolume | String | Quote asset traded |
| tradeCount | Integer | Trades in the bucket. `0` on a flat bucket |
