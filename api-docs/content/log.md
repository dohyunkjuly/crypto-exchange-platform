---
title: "{name} Spot API — Change Log"

---

# Upcoming changes

<aside class="notice">
Every entry on this page is sample text, to show the shape of the change log. The first real
entries are written with the first public release.
</aside>

## Sample: market-order caps per market

`GET /v1/markets/info` will carry `marketMinQty` and `marketMaxQty` for every market, and a market
order outside them will be refused with `5008`. Effective in production from **September 30, 2026**.

# 2026-09-16

## Sample: candles page back into history

`GET /v1/markets/{symbol}/candles` accepts `endTime`, the newest bucket wanted. A page shorter than
`limit` is the start of the market's history.

Applies to:

- `GET /v1/markets/{symbol}/candles`

# 2026-09-10

## Sample: the depthUpdate channel

The WebSocket carries `depthUpdate`: only the levels that changed, each with its new absolute size,
batched every 100 ms, for clients mirroring the whole ladder.

## Sample: order budget headers

Every placement reports `X-ORDER-COUNT-10S` and `X-ORDER-COUNT-1D`; a refusal carries `Retry-After`.

# 2026-09-01

## Sample: initial release

REST under `/v1` and the WebSocket at `/v1/ws`.
