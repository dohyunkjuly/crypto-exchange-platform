# Sockets

Two kinds of socket, both served by web-bff (`/bff/ws/market`, `/bff/ws/user`) and by
spot-open-api (`/v1/ws`, one endpoint for both). The public shapes are on the developer site;
this is how they are built and the rules that keep them safe.

## Frames are whole state, not diffs

Every frame carries the full state of what it describes: the whole ticker, the top of the book,
the whole candle, the order's full status. A client resyncs by replacing, never by applying, so
a dropped frame costs nothing and there is no diff logic to get wrong on either side.

**The one exception is `depthUpdate`** on spot-open-api, the full-ladder path for bots that
mirror the whole book. Each level in a frame is absolute (the new quantity, zero meaning gone),
but the frame lists only the levels that changed, so the client holds the book and applies frames
in sequence order. Absolute levels heal themselves: a client that misses one is corrected by the
next frame for that level, which is why every venue sends them rather than signed
deltas. The client procedure is on the developer site; the backend side is in
[depth-fold.md](depth-fold.md). web-bff never uses this channel.

## Two topics may drop frames

`exchange.spot.settlements.v1` and `exchange.spot.depth.v1` have finite retention, unlike every
command and event topic ([kafka-topics.md](kafka-topics.md)). They feed live pushes that a client
can recover over REST (`GET /v1/wallet/balances`, `GET /v1/orders/open`, the depth snapshot),
and a frame nobody is listening for is worth nothing. The docs tell clients to reconcile after
any reconnect.

## One fan-out, shared by both apps

The market socket's channels, publishers, events consumer, frame records and the handler live in
`libs/domain/marketdata`, package `stream`. The private half (`UserWsConsumer`,
`SettlementConsumer` and their frames) lives in `libs/domain/spot`, package `stream`, behind
`UserFanout`. Each app constructs the handler as its own bean rather than scanning it, and
supplies:

- `ProtocolErrors`: its own codes for protocol failures and for the channels' plain exceptions.
  A shared module owns no error code ([error-codes.md](error-codes.md)).
- `SocketLimits`: subscriptions per connection and the send buffer.
- `TickerSnapshot` and `DepthGroupings`: what the ticker and depth channels read.

Each app keeps its own per-instance consumer group, prefixed with the app's name, so every
instance of both apps receives every event ([kafka-topics.md](kafka-topics.md)).

## A lead account's private socket on web-bff

`/bff/ws/user` is the signed-in customer's. `/bff/ws/user?account=lead` is their lead
account's, for the trade screen trading as it ([copy-trading.md](copy-trading.md)): the
handshake resolves the account from the session and the connection is routed by that account's
id from then on. A customer with no lead account is refused with 403.

The web reads these frames as state, not as a bell to ring (`features/my/shared/UserStream.tsx`):
`orderUpdate` patches the open-orders row (a fill's by quantity less remaining, since the
engine's event has no cumulative figure), `balanceChanged` replaces the asset's row (skipping a
frame whose `version` is behind the row), and `fill` is not read: its orderUpdate already moved
the row. Nothing is fetched again per frame; an
order filling a hundred times is a hundred frames and no requests. The lists are fetched on
mount, on reconnect, and when an acceptance frame carries no row. The one time this was a
refetch per frame, a filling order tripped web-bff's 100-a-minute limit and the panel read
"Too many requests".

## Private frames on spot-open-api

The socket is cross-origin, so there is no cookie. It authenticates with the key rather than
with a listen-key: a listen-key is a second credential with its own table,
expiry and keepalive endpoint, whose only purpose is to avoid signing on the socket. Signing once
on connect (`auth` with `GET /v1/ws`) is cheaper. The same checks as the HTTP filter run, with
permission `can_read`. From then on every private frame for the account flows on the connection,
with no subscribe step: a bot that wants orders wants fills and balances too.

## Connection rules on spot-open-api

| Limit | Value | On breach |
|---|---|---|
| Inbound messages per connection | 10 a second | error frame 7009; closed after 10 seconds over |
| Subscriptions per connection | 50 | 7004 |
| Authenticated connections per key | 5 | 7010 on `auth` |
| Connections per IP | 20, `RATE_LIMIT_WS_CONNECTIONS_PER_IP` | closed, reason `too many connections`. The local stack lifts it for `bots/`: every bot on one machine is one IP, and each account holds a socket |
| Connection lifetime | 24 hours | closed with code 1000, reason `reconnect` |
| Send buffer | 256 KB, 5 seconds | closed: a slow bot must not hold up the Kafka thread fanning out to everyone |

The counts are per instance, in memory, since a connection lives on one instance. They are
separate from the REST weight buckets on purpose: a misbehaving socket cannot lock its key out
of REST, and REST traffic cannot starve a socket. Outbound is not rate-limited; the send buffer
is the only guard. The 24-hour lifetime serves the server more than the
client: every socket reconnects within a day, so a rolling deploy never leaves one pinned to an
instance that is gone. A key revoked while its sockets are open is dropped within a minute.

## In the browser

`web/` opens each socket once, through a provider, and components subscribe to channels on it.
Six panels opening their own would be six connections per tab and six reconnect storms on a
blip. Subscriptions are reference-counted and replayed on reconnect. Open orders apply an
`orderUpdate` in place; history tabs are not live because they page by offset.
