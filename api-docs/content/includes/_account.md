# Account

Signed endpoints. The key needs read permission.

## Get account

> Request

```shell
# Get account
GET /v1/account
```

```python
print(request("GET", "/v1/account"))
```

```javascript
console.log(await request("GET", "/v1/account"));
```

> Response

```json
{
    "status": 200,
    "code": 0,
    "data": {
        "uid": 4829301756,
        "kycLevel": "INTERMEDIATE",
        "emailVerified": true,
        "mfaEnabled": true,
        "canTrade": true,
        "key": {
            "label": "bot-1",
            "canRead": true,
            "canTrade": true,
            "canWithdraw": false,
            "expiresAt": null
        },
        "fees": {
            "makerBps": 10,
            "takerBps": 10,
            "makerPercent": "0.1000",
            "takerPercent": "0.1000",
            "tierLevel": 0,
            "tierName": "Regular",
            "custom": false
        }
    },
    "message": "OK"
}
```

Retrieve the account information.

**Rate limit:** weight 10

**HTTP request**

`GET /v1/account`

**Response parameters**

| Parameter | Type | Description |
|---|---|---|
| uid | Integer | Account id |
| kycLevel | String | `NONE`<br>`BASIC`<br>`INTERMEDIATE`<br>`ADVANCED` |
| emailVerified | Boolean | Whether the email is verified |
| mfaEnabled | Boolean | Whether a second factor is active |
| canTrade | Boolean | Whether the account may place orders. `false` while the email is unverified, KYC is below `BASIC` or the second factor is off |
| key | Object | The calling key |
| > label | String | Label given at creation |
| > canRead | Boolean | Read permission |
| > canTrade | Boolean | Trade permission |
| > canWithdraw | Boolean | Withdraw permission. Always `false`, withdrawals are not exposed on this API |
| > expiresAt | String | Expiry, ISO-8601 UTC. `null` when the key does not expire |
| fees | Object | Spot fee rates in force |
| > makerBps | Integer | Maker fee, basis points. Negative is a rebate |
| > takerBps | Integer | Taker fee, basis points |
| > makerPercent | String | Maker fee in percent |
| > takerPercent | String | Taker fee in percent |
| > tierLevel | Integer | Fee tier. Absent on a negotiated rate |
| > tierName | String | Fee tier name. Absent on a negotiated rate |
| > custom | Boolean | Whether the rate is negotiated rather than tiered |

## Get balances

> Request

```shell
# Get balances
GET /v1/wallet/balances
```

```python
print(request("GET", "/v1/wallet/balances"))
```

```javascript
console.log(await request("GET", "/v1/wallet/balances"));
```

> Response

```json
{
    "status": 200,
    "code": 0,
    "data": [
        {
            "asset": "USDT",
            "available": "999245.12",
            "locked": "755.02",
            "total": "1000000.14",
            "version": 118
        },
        {
            "asset": "BTC",
            "available": "1.00000",
            "locked": "0",
            "total": "1.00000",
            "version": 7
        }
    ],
    "message": "OK"
}
```

Retrieve every balance the account holds.

**Rate limit:** weight 10

**HTTP request**

`GET /v1/wallet/balances`

**Response parameters**

| Parameter | Type | Description |
|---|---|---|
| asset | String | Asset symbol |
| available | String | Free to trade |
| locked | String | Held by open orders |
| total | String | `available + locked` |
| version | Integer | Row version, increases with every change to this asset. Apply a pushed `balanceChanged` only when its `version` is higher |
