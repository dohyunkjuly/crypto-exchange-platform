"""The reference feed, read-only: a large venue's public spot market, which our bots follow.

No key, no signing, nothing is ever sent to it. The bots read a real market so that ours
moves the way a real one does -- with the volatility, the quiet hours and the jumps that no
random walk produces convincingly.

Same shape as ``SpotAsyncClient``: transport only, a named method per endpoint, and ``ws_*``
methods that open a stream and hand it back for the caller to read.
"""

import logging
from typing import Any

import httpx
import websockets

__all__ = ["ReferenceFeedClient"]


class ReferenceFeedClient:

    def __init__(
        self,
        *,
        rest_url: str = "https://api.binance.com",
        stream_url: str = "wss://stream.binance.com:9443",
        request_timeout: float = 10.0,
    ):
        self.request_timeout = request_timeout
        self._base = rest_url.rstrip("/")
        self._stream = stream_url.rstrip("/")
        self._session = None
        self._logger = logging.getLogger(self.__class__.__name__)

    @classmethod
    async def create(cls, **options: Any) -> "ReferenceFeedClient":
        client = cls(**options)
        await client._init_session()
        return client

    async def _init_session(self) -> None:
        if self._session is None or self._session.is_closed:
            self._session = httpx.AsyncClient(timeout=self.request_timeout)

    async def close(self) -> None:
        if self._session is not None and not self._session.is_closed:
            await self._session.aclose()

    async def _request(self, method: str, path: str, params: dict | None = None) -> Any:
        if self._session is None or self._session.is_closed:
            await self._init_session()
        params = {k: v for k, v in (params or {}).items() if v is not None}
        response = await self._session.request(method, self._base + path, params=params)
        if response.status_code != 200:
            self._logger.error("feed request failed: %s %s -> %s %s",
                               method, path, response.status_code, response.text[:200])
        response.raise_for_status()
        return response.json()

    # ------------------------------------------------------------------ REST

    async def get_exchange_info(self, symbol: str | None = None) -> dict:
        return await self._request("GET", "/api/v3/exchangeInfo", {"symbol": symbol})

    async def get_ticker_price(self, symbol: str | None = None) -> Any:
        """One ``{symbol, price}``, or with no symbol, every market's in a list."""
        return await self._request("GET", "/api/v3/ticker/price", {"symbol": symbol})

    async def get_ticker_book(self, symbol: str | None = None) -> Any:
        """Best bid and ask, with their sizes."""
        return await self._request("GET", "/api/v3/ticker/bookTicker", {"symbol": symbol})

    async def get_depth(self, symbol: str, limit: int | None = None) -> dict:
        return await self._request("GET", "/api/v3/depth", {"symbol": symbol, "limit": limit})

    async def get_recent_trades(self, symbol: str, limit: int | None = None) -> list[dict]:
        return await self._request("GET", "/api/v3/trades", {"symbol": symbol, "limit": limit})

    async def get_klines(self, symbol: str, interval: str, limit: int | None = None,
                         start_time: int | None = None, end_time: int | None = None) -> list[list]:
        return await self._request("GET", "/api/v3/klines", {
            "symbol": symbol, "interval": interval, "limit": limit,
            "startTime": start_time, "endTime": end_time})

    # ------------------------------------------------------------------ websocket

    async def ws_trade(self, symbol: str):
        return await self._connect_websocket(f"{self._stream}/ws/{symbol.lower()}@trade")

    async def ws_agg_trade(self, symbol: str):
        return await self._connect_websocket(f"{self._stream}/ws/{symbol.lower()}@aggTrade")

    async def ws_book_ticker(self, symbol: str):
        return await self._connect_websocket(f"{self._stream}/ws/{symbol.lower()}@bookTicker")

    async def ws_mini_ticker(self, symbol: str):
        return await self._connect_websocket(f"{self._stream}/ws/{symbol.lower()}@miniTicker")

    async def ws_klines(self, symbol: str, interval: str):
        return await self._connect_websocket(f"{self._stream}/ws/{symbol.lower()}@kline_{interval}")

    async def ws_depth(self, symbol: str, levels: int = 20, speed_ms: int = 100):
        return await self._connect_websocket(
            f"{self._stream}/ws/{symbol.lower()}@depth{levels}@{speed_ms}ms")

    async def ws_combined(self, streams: list[str]):
        """Many streams on one connection, e.g. ``["btcusdt@trade", "ethusdt@trade"]``.

        One connection for a dozen markets rather than one each: the venue counts connections,
        and a dozen sockets are a dozen things that can drop separately. Frames arrive wrapped
        as ``{"stream": ..., "data": ...}``.
        """
        return await self._connect_websocket(f"{self._stream}/stream?streams={'/'.join(streams)}")

    async def _connect_websocket(self, url: str):
        # A short close: these are read-only streams, and the venue takes seconds to answer a
        # close frame -- seconds a stopping controller would spend waiting for nothing.
        return await websockets.connect(url, ping_interval=20, close_timeout=1)
