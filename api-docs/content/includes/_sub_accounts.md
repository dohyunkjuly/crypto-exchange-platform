# Sub-accounts

Separate balances, orders and keys under one identity. A master account creates sub-accounts,
moves funds between them, mints their keys (on the web UI, not here) and freezes them. A
sub-account cannot log in and has no verification of its own: it trades because its master may.
One level only: a sub-account cannot have sub-accounts. At most 20 per master.

Signed endpoints, **with the master's key**. A sub-account's own key is refused here with
`2111`; everywhere else it is an ordinary account. Reads need read permission; creating,
freezing and transferring need trade permission.

## Create a sub-account

> Request

```shell
# Create a sub-account
POST /v1/sub-accounts
{"label": "arb bot"}
```

```python
print(request("POST", "/v1/sub-accounts", {"label": "arb bot"}))
```

```javascript
console.log(await request("POST", "/v1/sub-accounts", { label: "arb bot" }));
```

> Response

```json
{
    "status": 201,
    "code": 0,
    "data": {
        "subAccountId": 7305916284,
        "email": "k3j9x2p1_virtual@wv7vw8elnq.com",
        "label": "arb bot",
        "frozen": false,
        "createdAt": "2026-09-22T08:00:00.000Z"
    },
    "message": "OK"
}
```

**Rate limit:** weight 1. Counts against nothing else.

**HTTP request**

`POST /v1/sub-accounts`

**Request parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| label | String | Yes | What you call it. Up to 64 characters |

**Response parameters**

| Parameter | Type | Description |
|---|---|---|
| subAccountId | Integer | The sub-account's user id: what transfers and other calls name it by |
| email | String | Assigned, virtual: an identifier, not a mailbox. Nothing logs in with it, and nothing is sent to it |
| label | String | As given |
| frozen | Boolean | Whether it is frozen |
| createdAt | String | ISO-8601 |

## List sub-accounts

> Request

```shell
# List sub-accounts
GET /v1/sub-accounts
```

```python
print(request("GET", "/v1/sub-accounts"))
```

```javascript
console.log(await request("GET", "/v1/sub-accounts"));
```

> Response

```json
{
    "status": 200,
    "code": 0,
    "data": [
        {
            "subAccountId": 7305916284,
            "email": "k3j9x2p1_virtual@wv7vw8elnq.com",
            "label": "arb bot",
            "frozen": false,
            "createdAt": "2026-09-22T08:00:00.000Z"
        }
    ],
    "message": "OK"
}
```

**Rate limit:** weight 5

**HTTP request**

`GET /v1/sub-accounts`

## Get a sub-account's balances

> Request

```shell
# Get a sub-account's balances
GET /v1/sub-accounts/7305916284/balances
```

```python
print(request("GET", "/v1/sub-accounts/7305916284/balances"))
```

```javascript
console.log(await request("GET", "/v1/sub-accounts/7305916284/balances"));
```

> Response

```json
{
    "status": 200,
    "code": 0,
    "data": [
        {
            "asset": "USDT",
            "available": "250",
            "locked": "0",
            "total": "250",
            "version": 3
        }
    ],
    "message": "OK"
}
```

The same shape as `GET /v1/wallet/balances`, for the sub-account. `2113` if it is not yours.

**Rate limit:** weight 10

**HTTP request**

`GET /v1/sub-accounts/{subAccountId}/balances`

## Freeze or unfreeze a sub-account

> Request

```shell
# Freeze a sub-account
PATCH /v1/sub-accounts/7305916284/frozen
{"frozen": true}
```

```python
print(request("PATCH", "/v1/sub-accounts/7305916284/frozen", {"frozen": True}))
```

```javascript
console.log(await request("PATCH", "/v1/sub-accounts/7305916284/frozen", { frozen: true }));
```

> Response

```json
{
    "status": 200,
    "code": 0,
    "data": {
        "subAccountId": 7305916284,
        "email": "k3j9x2p1_virtual@wv7vw8elnq.com",
        "label": "arb bot",
        "frozen": true,
        "createdAt": "2026-09-22T08:00:00.000Z"
    },
    "message": "OK"
}
```

A frozen sub-account cannot place or amend orders (`2110` on its own key), and no transfer may
reach or leave it (`2114`). Its open orders stay, and it can still cancel them: cancelling is
never gated. Its keys keep working for reads and cancels.

**Rate limit:** weight 1

**HTTP request**

`PATCH /v1/sub-accounts/{subAccountId}/frozen`

**Request parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| frozen | Boolean | Yes | `true` to freeze, `false` to unfreeze |

## Transfer

> Request

```shell
# Transfer between the master and a sub-account, or between two sub-accounts
POST /v1/sub-accounts/transfers
{"fromUserId": 4829301756, "toUserId": 7305916284, "asset": "USDT", "amount": "250", "transferId": "0192b3c4-5d6e-7f80-9a1b-2c3d4e5f6071"}
```

```python
print(request("POST", "/v1/sub-accounts/transfers", {
    "fromUserId": 4829301756, "toUserId": 7305916284, "asset": "USDT", "amount": "250",
    "transferId": "0192b3c4-5d6e-7f80-9a1b-2c3d4e5f6071"}))
```

```javascript
console.log(await request("POST", "/v1/sub-accounts/transfers", {
  fromUserId: 4829301756, toUserId: 7305916284, asset: "USDT", amount: "250",
  transferId: "0192b3c4-5d6e-7f80-9a1b-2c3d4e5f6071",
}));
```

> Response

```json
{
    "status": 201,
    "code": 0,
    "data": {
        "transferId": "0192b3c4-5d6e-7f80-9a1b-2c3d4e5f6071",
        "fromUserId": 4829301756,
        "toUserId": 7305916284,
        "asset": "USDT",
        "amount": "250",
        "createdAt": "2026-09-22T08:00:01.000Z"
    },
    "message": "OK"
}
```

Instant. Moves from the sender's available balance; `4001` if it is short, and nothing moves.
Either side is the master (its `uid` from `GET /v1/account`) or one of its sub-accounts.

**Send `transferId`.** A UUID you choose. A retry after a timeout with the same id returns the
transfer that was made and moves nothing again. Without it, every call is a new transfer.

**Rate limit:** weight 1

**HTTP request**

`POST /v1/sub-accounts/transfers`

**Request parameters**

| Parameter | Type | Required | Description |
|---|---|---|---|
| fromUserId | Integer | Yes | The master's uid or a sub-account's id |
| toUserId | Integer | Yes | The master's uid or a sub-account's id |
| asset | String | Yes | Asset symbol |
| amount | String | Yes | Decimal string, positive |
| transferId | String | No | Your UUID for this transfer, for a safe retry |

## Transfer history

> Request

```shell
# Transfer history
GET /v1/sub-accounts/transfers
```

```python
print(request("GET", "/v1/sub-accounts/transfers"))
```

```javascript
console.log(await request("GET", "/v1/sub-accounts/transfers"));
```

> Response

```json
{
    "status": 200,
    "code": 0,
    "data": [
        {
            "transferId": "0192b3c4-5d6e-7f80-9a1b-2c3d4e5f6071",
            "fromUserId": 4829301756,
            "toUserId": 7305916284,
            "asset": "USDT",
            "amount": "250",
            "createdAt": "2026-09-22T08:00:01.000Z"
        }
    ],
    "message": "OK"
}
```

The last 100 transfers touching the master or any of its sub-accounts, newest first.

**Rate limit:** weight 5

**HTTP request**

`GET /v1/sub-accounts/transfers`
