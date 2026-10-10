# spot-open-api

The public, signed API for bots, on :8080: HMAC-SHA256 request signing, per-endpoint rate
limits, REST under `/v1` and one socket at `/v1/ws`. It is a public promise: fields are added,
never renamed or removed, and a breaking change is `/v2`. The developer site in `api-docs/`
documents it, and changes to an endpoint, field, channel, code or limit are made there in the
same commit.

It adds no domain logic. Every capability is a shared service in `libs/domain/*` that web-bff
also uses; this app is a second edge with a different credential, its own error codes and its
own copies of the response shapes.

- **Publishes:** orders to `exchange.spot.commands.v1`.
- **Consumes:** `events.v1`, `settlements.v1` and `depth.v1` for the socket.
- Holds no state; keys are rows, weights and order budgets are in Redis.

The signing filter, the gates and how the limits are counted:
[api-signing-and-limits.md](../../../docs/reference/api-signing-and-limits.md). The sockets and their connection
rules: [sockets.md](../../../docs/reference/sockets.md). Deposits and withdrawals are absent on purpose:
[white-label.md](../../../docs/reference/white-label.md).
