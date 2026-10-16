# web-bff

The backend for `web/` and nothing else, on :8081. It owns the session cookie, sign-up, login
with a second factor, KYC, API-key management and the trading screen's data, under `/bff/*`.
Its shape changes with the UI and is not a public surface; bots use spot-open-api.

- **Serves:** REST under `/bff`, the market socket `/bff/ws/market` and the private socket
  `/bff/ws/user`.
- **Publishes:** orders to `exchange.spot.commands.v1` through the shared `OrderService`.
- **Consumes:** `events.v1` and `settlements.v1` for the sockets, one consumer group per
  instance so every instance can fan out.
- Holds no state; sessions are rows, rate limits are in Redis.
- **Two clients, one session:** the browser's httpOnly cookie, and the mobile app's
  `Authorization: Bearer`, the same token, handed back as `X-Session-Token` only to a login sent
  with `X-Session-Delivery: bearer` (`SessionCredentials`).

Why the browser talks only to this app and how the session gate works:
[frontend-session.md](../../../docs/reference/frontend-session.md). Where the trading gate sits:
[trading-gate.md](../../../docs/reference/trading-gate.md). The sockets: [sockets.md](../../../docs/reference/sockets.md).
