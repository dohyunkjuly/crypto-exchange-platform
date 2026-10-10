"""Our exchange: every REST endpoint and every websocket stream, in one class.

It only talks to the exchange -- signs, sends, returns the answer. No caching, no background
tasks. Two hosts:

    api_request   spot-open-api, the trading API. Signed.
    dev_request   web-bff /dev. Not signed. Exists only on a local dev stack: create and fund accounts.

Never retry a request that changes something. Placing an order answers 202 before it is matched,
so a timeout does not tell you whether the order exists. Send a client_order_id and read it back.
"""

import hashlib
import hmac
import json
import time
from decimal import Decimal
from typing import Any
from urllib.parse import quote, urlencode

import httpx
import websockets

from ..utils.money import fmt

__all__ = ["SpotAsyncClient", "SpotApiError"]

class SpotApiError(Exception):
    """The exchange answered with an error. `status` is the HTTP status, `code` the exchange's own."""

    def __init__(self, status: int, code: int | None, message: str, path: str):
        self.status = status
        self.code = code
        self.message = message
        self.path = path
        super().__init__(f"{path} -> HTTP {status} code {code}: {message}")


class SpotAsyncClient:

    def __init__(
        self,
        api_key: str | None = None,
        api_secret: str | None = None,
        *,
        api_url: str = "http://localhost:8080",
        dev_url: str = "http://localhost:8081",
        request_timeout: float = 15.0,
    ):
        """One client is one account. No key is fine for public data and /dev."""
        self.api_key = api_key
        self.api_secret = api_secret
        self.request_timeout = request_timeout

        self._api_url = api_url.rstrip("/")
        self._dev_url = dev_url.rstrip("/")
        self._ws_url = self._api_url.replace("https://", "wss://").replace("http://", "ws://") + "/v1/ws"

        self._session = None
        self._ws_request_id = 0

    @classmethod
    async def create(cls, api_key: str | None = None, api_secret: str | None = None,
                     **options: Any) -> "SpotAsyncClient":
        client = cls(api_key, api_secret, **options)
        await client._init_session()
        return client

    async def _init_session(self) -> None:
        if self._session is None or self._session.is_closed:
            self._session = httpx.AsyncClient(timeout=self.request_timeout)

    async def close(self) -> None:
        if self._session is not None and not self._session.is_closed:
            await self._session.aclose()

    # ------------------------------------------------------------------ signing

    @staticmethod
    def _get_timestamp() -> int:
        return int(time.time() * 1000)

    @staticmethod
    def _generate_signature(api_secret: str, timestamp: str, method: str, path: str, body: str = "") -> str:
        """HMAC-SHA256 of timestamp + METHOD + path (with its query) + body, as lowercase hex."""
        payload = f"{timestamp}{method.upper()}{path}{body}"
        return hmac.new(api_secret.encode(), payload.encode(), hashlib.sha256).hexdigest()

    def _signed_headers(self, method: str, path: str, body: str = "") -> dict[str, str]:
        if not self.api_key or not self.api_secret:
            raise ValueError(f"{method} {path} needs an API key, and this client has none")
        timestamp = str(self._get_timestamp())
        return {
            "X-ACCESS-KEY": self.api_key,
            "X-ACCESS-TIMESTAMP": timestamp,
            "X-ACCESS-SIGN": self._generate_signature(self.api_secret, timestamp, method, path, body),
        }

    # ------------------------------------------------------------------ requests

    @staticmethod
    def _to_wire(value: Any) -> Any:
        """A Decimal goes out as a plain string like "0.00000001", never a float, never "1E-8"."""
        return fmt(value) if isinstance(value, Decimal) else value

    @staticmethod
    def _order_path(order_id: str | None, client_order_id: str | None) -> str:
        if order_id:
            return f"/v1/orders/{quote(order_id)}"
        if client_order_id:
            return f"/v1/orders/by-client-id/{quote(client_order_id)}"
        raise ValueError("either order_id or client_order_id must be provided")

    async def _request(self, method: str, base_url: str, path: str, *, signed: bool = False,
                       params: dict | None = None, body: dict | None = None) -> Any:
        if self._session is None or self._session.is_closed:
            await self._init_session()

        # Built here, once, because these exact strings are what gets signed.
        query = urlencode({k: self._to_wire(v) for k, v in (params or {}).items() if v is not None})
        if query:
            path = f"{path}?{query}"
        body_text = ""
        if body is not None:
            body_text = json.dumps({k: self._to_wire(v) for k, v in body.items() if v is not None},
                                   separators=(",", ":"))

        headers = self._signed_headers(method, path, body_text) if signed else {}
        if body_text:
            headers["Content-Type"] = "application/json"
        response = await self._session.request(method, base_url + path, headers=headers,
                                               content=body_text.encode() if body_text else None)
        return self._parse_response(response, path)

    @staticmethod
    def _parse_response(response: httpx.Response, path: str) -> Any:
        """Every answer is {status, code, data, message}. Returns `data`, or raises SpotApiError."""
        try:
            answer = response.json()
        except ValueError:
            raise SpotApiError(response.status_code, None, response.text[:200], path) from None
        if response.is_success:
            return answer.get("data")
        raise SpotApiError(response.status_code, answer.get("code"), answer.get("message") or "", path)

    async def api_request(self, method: str, path: str, *, signed: bool = False,
                          params: dict | None = None, body: dict | None = None) -> Any:
        return await self._request(method, self._api_url, path, signed=signed, params=params, body=body)

    async def dev_request(self, method: str, path: str, *, body: dict | None = None) -> Any:
        try:
            return await self._request(method, self._dev_url, path, body=body)
        except SpotApiError as e:
            if e.status == 404:
                raise SpotApiError(404, e.code, f"{self._dev_url}{path} not found: /dev only exists "
                                   "on a stack running the dev profile", path) from None
            raise

    # ------------------------------------------------------------------ dev (local stack only)

    async def create_dev_accounts(self, count: int, label: str, fund: str) -> dict:
        """`count` NEW accounts, ready to trade, each funded `fund` of every asset.

        Returns {"password", "accounts": [{email, userId, apiKey, apiSecret, totpSecret}]}.
        The API secrets are returned this once and can never be fetched again.
        """
        return await self.dev_request("POST", "/dev/accounts",
                                      body={"count": count, "label": label, "fund": fund})

    async def dev_fund(self, email: str, asset: str, amount: Decimal | str, reference: str) -> dict:
        """Credits one account. The same `reference` twice credits once."""
        return await self.dev_request("POST", "/dev/fund", body={
            "email": email, "asset": asset, "amount": amount, "reference": reference})

    # ------------------------------------------------------------------ market data (public)

    async def get_server_time(self) -> dict:
        return await self.api_request("GET", "/v1/time")

    async def get_markets_info(self) -> dict:
        """Every market with its order rules (tick size, min quantity, ...), and the rate limits."""
        return await self.api_request("GET", "/v1/markets/info")

    async def get_tickers(self) -> list[dict]:
        return await self.api_request("GET", "/v1/markets/tickers")

    async def get_ticker(self, symbol: str) -> dict:
        """Raises SpotApiError with code 6002 on a market that has never traded."""
        return await self.api_request("GET", f"/v1/markets/{quote(symbol)}/ticker")

    async def get_depth(self, symbol: str, limit: int | None = None, grouping: str | None = None) -> dict:
        return await self.api_request("GET", f"/v1/markets/{quote(symbol)}/depth",
                                      params={"limit": limit, "grouping": grouping})

    async def get_recent_trades(self, symbol: str, limit: int | None = None) -> list[dict]:
        return await self.api_request("GET", f"/v1/markets/{quote(symbol)}/trades", params={"limit": limit})

    async def get_candles(self, symbol: str, interval: str, limit: int | None = None,
                          start_time: str | None = None, end_time: str | None = None) -> list[dict]:
        return await self.api_request("GET", f"/v1/markets/{quote(symbol)}/candles", params={
            "interval": interval, "limit": limit, "startTime": start_time, "endTime": end_time})

    # ------------------------------------------------------------------ account (signed)

    async def get_account(self) -> dict:
        """Who this key belongs to, whether it may trade, and its fee rates."""
        return await self.api_request("GET", "/v1/account", signed=True)

    async def get_balances(self) -> list[dict]:
        return await self.api_request("GET", "/v1/wallet/balances", signed=True)

    async def get_my_trades(self, symbol: str | None = None, limit: int | None = None,
                            start_time: str | None = None, end_time: str | None = None) -> list[dict]:
        return await self.api_request("GET", "/v1/trades", signed=True, params={
            "symbol": symbol, "limit": limit, "startTime": start_time, "endTime": end_time})

    async def get_open_orders(self, symbol: str | None = None) -> list[dict]:
        return await self.api_request("GET", "/v1/orders/open", signed=True, params={"symbol": symbol})

    async def get_order_history(self, symbol: str | None = None, limit: int | None = None,
                                start_time: str | None = None, end_time: str | None = None) -> list[dict]:
        return await self.api_request("GET", "/v1/orders", signed=True, params={
            "symbol": symbol, "limit": limit, "startTime": start_time, "endTime": end_time})

    async def get_order(self, order_id: str) -> dict:
        return await self.api_request("GET", f"/v1/orders/{quote(order_id)}", signed=True)

    # ------------------------------------------------------------------ orders (signed, never retried)

    async def create_order(
        self,
        symbol: str,
        side: str,
        order_type: str,
        quantity: Decimal | None = None,
        price: Decimal | None = None,
        time_in_force: str | None = None,
        quote_order_qty: Decimal | None = None,
        stop_price: Decimal | None = None,
        trailing_delta_bps: int | None = None,
        client_order_id: str | None = None,
        self_trade_prevention_mode: str | None = None,
    ) -> dict:
        """Answers 202 with the order, before the engine has matched anything.

        A BUY of type MARKET, STOP_LOSS or TAKE_PROFIT is sized by `quote_order_qty` (how much
        to spend). Everything else is sized by `quantity`.
        """
        return await self.api_request("POST", "/v1/orders", signed=True, body={
            "symbol": symbol,
            "side": side,
            "orderType": order_type,
            "timeInForce": time_in_force,
            "price": price,
            "stopPrice": stop_price,
            "quantity": quantity,
            "quoteOrderQty": quote_order_qty,
            "clientOrderId": client_order_id,
            "selfTradePreventionMode": self_trade_prevention_mode,
            "trailingDeltaBps": trailing_delta_bps,
        })

    async def amend_order(
        self,
        order_id: str | None = None,
        client_order_id: str | None = None,
        price: Decimal | None = None,
        stop_price: Decimal | None = None,
        quantity: Decimal | None = None,
        quote_order_qty: Decimal | None = None,
    ) -> dict:
        """`quantity` is the new TOTAL size, filled part included. One amend at a time per order."""
        path = self._order_path(order_id, client_order_id)
        return await self.api_request("PATCH", path, signed=True, body={
            "price": price, "stopPrice": stop_price,
            "quantity": quantity, "quoteOrderQty": quote_order_qty})

    async def cancel_order(self, order_id: str | None = None, client_order_id: str | None = None) -> dict:
        return await self.api_request("DELETE", self._order_path(order_id, client_order_id), signed=True)

    async def cancel_all_orders(self, symbol: str | None = None, side: str | None = None,
                                order_type: str | None = None, scope: str | None = None) -> dict:
        """Cancels every open order matching the filters. `scope`: ALL, BOOK or TRIGGERS."""
        return await self.api_request("DELETE", "/v1/orders/open", signed=True, params={
            "symbol": symbol, "side": side, "type": order_type, "scope": scope})

    # ------------------------------------------------------------------ websocket
    #
    # Each ws_* opens a connection, subscribes, and returns it. The caller reads it:
    #
    #     ws = await client.ws_trade("BTCUSDT")
    #     async for message in ws:
    #         frame = json.loads(message)
    #
    # Nothing is replayed after a reconnect: re-read open orders and balances over REST.

    async def ws_ticker(self, symbol: str):
        return await self._ws_open_and_subscribe("ticker", symbol)

    async def ws_trade(self, symbol: str):
        return await self._ws_open_and_subscribe("trade", symbol)

    async def ws_depth(self, symbol: str, grouping: str | None = None):
        """The top 50 levels of each side, sent whole on every change."""
        return await self._ws_open_and_subscribe("depth", symbol, grouping=grouping)

    async def ws_depth_update(self, symbol: str):
        """The full book, as changes only. The client has to keep the book itself."""
        return await self._ws_open_and_subscribe("depthUpdate", symbol)

    async def ws_klines(self, symbol: str, interval: str):
        return await self._ws_open_and_subscribe("kline", symbol, interval=interval)

    async def ws_user_data(self):
        """This account's own orderUpdate, fill and balanceChanged frames."""
        ws = await self._connect_websocket()
        await self._ws_login(ws)
        return ws

    async def ws_subscribe(self, ws, channel: str, symbol: str, **options: Any) -> None:
        """Adds a subscription to a connection that is already open. 50 per connection at most."""
        request = {"id": self._next_ws_request_id(), "action": "subscribe",
                   "channel": channel, "symbol": symbol}
        request.update({k: v for k, v in options.items() if v is not None})
        await ws.send(json.dumps(request))

    async def ws_unsubscribe(self, ws, channel: str, symbol: str) -> None:
        await ws.send(json.dumps({"id": self._next_ws_request_id(), "action": "unsubscribe",
                                  "channel": channel, "symbol": symbol}))

    async def _ws_open_and_subscribe(self, channel: str, symbol: str, **options: Any):
        ws = await self._connect_websocket()
        await self.ws_subscribe(ws, channel, symbol, **options)
        return ws

    async def _connect_websocket(self):
        return await websockets.connect(self._ws_url, ping_interval=20, close_timeout=5)

    async def _ws_login(self, ws) -> None:
        if not self.api_key or not self.api_secret:
            raise ValueError("ws_user_data needs an API key, and this client has none")
        timestamp = str(self._get_timestamp())
        await ws.send(json.dumps({
            "id": self._next_ws_request_id(),
            "action": "auth",
            "apiKey": self.api_key,
            "timestamp": timestamp,
            # The socket is never called over REST; "GET /v1/ws" is simply the agreed string to sign.
            "signature": self._generate_signature(self.api_secret, timestamp, "GET", "/v1/ws"),
        }))

    def _next_ws_request_id(self) -> int:
        self._ws_request_id += 1
        return self._ws_request_id
