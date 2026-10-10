# Authentication

> Signed request

```shell
# A signed request
GET /v1/account
X-ACCESS-KEY: ak_Zm9vYmFyYmF6cXV4MTIzNDU2
X-ACCESS-TIMESTAMP: 1757800000000
X-ACCESS-SIGN: 98160e1df1a086905504e104785be2ce5963f7d955ce2a297d0a49cc51af067d
```

```python
import hmac, hashlib, json, time, urllib.request

BASE = "https://api.example.com"
API_KEY = "ak_..."
SECRET = "..."

def request(method, path, body=None):
    raw = json.dumps(body, separators=(",", ":")) if body is not None else ""
    ts = str(int(time.time() * 1000))
    canonical = ts + method.upper() + path + raw
    signature = hmac.new(SECRET.encode(), canonical.encode(),
                         hashlib.sha256).hexdigest()
    req = urllib.request.Request(BASE + path, method=method,
        data=raw.encode() if raw else None, headers={
            "X-ACCESS-KEY": API_KEY,
            "X-ACCESS-TIMESTAMP": ts,
            "X-ACCESS-SIGN": signature,
            "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        return json.load(e)

print(request("GET", "/v1/account"))
```

```javascript
import { createHmac } from "node:crypto";

const BASE = "https://api.example.com";
const API_KEY = "ak_...";
const SECRET = "...";

async function request(method: string, path: string, body?: unknown) {
  const raw = body === undefined ? "" : JSON.stringify(body);
  const ts = String(Date.now());
  const signature = createHmac("sha256", SECRET)
    .update(ts + method.toUpperCase() + path + raw)
    .digest("hex");
  const res = await fetch(BASE + path, {
    method,
    body: raw || undefined,
    headers: {
      "X-ACCESS-KEY": API_KEY,
      "X-ACCESS-TIMESTAMP": ts,
      "X-ACCESS-SIGN": signature,
      "Content-Type": "application/json",
    },
  });
  return res.json();
}

console.log(await request("GET", "/v1/account"));
```

API keys are created on the web UI under **API Management**. A key has read permission, trade
permission, an optional IP allowlist and an optional expiry. The secret is shown once, at creation.

## Signing

Private requests carry these headers:

| Header | Required | Description |
|---|---|---|
| X-ACCESS-KEY | Yes | API key |
| X-ACCESS-TIMESTAMP | Yes | Request time, epoch milliseconds |
| X-ACCESS-SIGN | Yes | Signature, lowercase hex |
| X-ACCESS-RECV-WINDOW | No | Milliseconds the request stays valid. Default `5000`, maximum `60000` |

The signature is `HMAC-SHA256(secret, timestamp + method + path + body)`:

| Part | Description |
|---|---|
| timestamp | The value of `X-ACCESS-TIMESTAMP` |
| method | Upper case: `GET`, `POST`, `DELETE` |
| path | Path and query string as sent, e.g. `/v1/orders/open?symbol=BTCUSDT` |
| body | Request body bytes as sent. Empty for `GET` and `DELETE` |

The WebSocket `auth` message signs `timestamp + "GET" + "/v1/ws"`.

## Common mistakes

| Symptom | Cause |
|---|---|
| `2102` on every request | The body was serialised again after signing, changing key order or spacing, or the query string was left out of the signed path |
| `2103` | The client clock is off by more than five seconds. Send `X-ACCESS-RECV-WINDOW` up to `60000`, or fix the clock |
| `2105` on placing | The key was created without trade permission |
| `2107` or `2108` | The account, not the key: identity verification lapsed, or the second factor is off |
| `429` with `Retry-After` | Over the weight budget per IP or the order budget per key. The refused request still counts |

