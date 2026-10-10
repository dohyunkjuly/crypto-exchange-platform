import json

import httpx
import pytest

from bots.clients.spot_client import SpotApiError, SpotAsyncClient
from bots.utils.money import D

KEY, SECRET = "ak_test", "secret-under-test"
TS = 1_700_000_000_000
API, DEV = "http://open-api:8080", "http://web-bff:8081"

# Produced by openssl, not by this module, so the test compares the implementation against
# something independent rather than against itself:
#   printf '%s' '1700000000000GET/v1/wallet/balances' \
#     | openssl dgst -sha256 -hmac 'secret-under-test' -hex
GET_BALANCES = "a3cff072747331ee67ac53a39959e41d4b71f5c463a206092dc1f2e521cc52d5"
POST_ORDER = "29e2c80096572f2d22c9099da21f99e4821ced7c1f3b45eb85f0f8f03741b86e"

sign = SpotAsyncClient._generate_signature


def client_for(handler, *, keyed: bool = True) -> tuple[SpotAsyncClient, list[httpx.Request]]:
    seen: list[httpx.Request] = []

    def record(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return handler(request)

    key, secret = (KEY, SECRET) if keyed else (None, None)
    client = SpotAsyncClient(key, secret, api_url=API, dev_url=DEV)
    # The client opens its own session; a test hands it one that answers from `handler`.
    client._session = httpx.AsyncClient(transport=httpx.MockTransport(record))
    return client, seen


def ok(data) -> httpx.Response:
    return httpx.Response(200, json={"status": 200, "code": 0, "data": data, "message": "OK"})


def refused(status: int, code: int, message: str = "no", headers=None) -> httpx.Response:
    return httpx.Response(status, json={"status": status, "code": code, "data": None, "message": message},
                          headers=headers or {})


class TestSigning:
    def test_matches_an_independently_computed_signature(self):
        assert sign(SECRET, str(TS), "GET", "/v1/wallet/balances") == GET_BALANCES
        assert sign(SECRET, str(TS), "POST", "/v1/orders", '{"symbol":"BTCUSDT"}') == POST_ORDER

    def test_the_method_is_signed_in_upper_case(self):
        assert sign(SECRET, str(TS), "get", "/v1/wallet/balances") == GET_BALANCES

    def test_the_query_is_part_of_what_is_signed(self):
        """Signing the bare path would let anyone edit the query of a captured request."""
        assert sign(SECRET, str(TS), "GET", "/v1/orders/open") != sign(
            SECRET, str(TS), "GET", "/v1/orders/open?symbol=BTCUSDT")

    def test_the_body_is_part_of_what_is_signed(self):
        """The whole reason the signed body is part of the signature: orders travel in the body."""
        assert sign(SECRET, str(TS), "POST", "/v1/orders", '{"quantity":"1"}') != sign(
            SECRET, str(TS), "POST", "/v1/orders", '{"quantity":"1000"}')

    def test_headers_carry_the_key_time_and_signature(self, monkeypatch):
        monkeypatch.setattr(SpotAsyncClient, "_get_timestamp", staticmethod(lambda: TS))
        client, _ = client_for(lambda r: ok({}))

        assert client._signed_headers("GET", "/v1/wallet/balances") == {
            "X-ACCESS-KEY": KEY, "X-ACCESS-TIMESTAMP": str(TS), "X-ACCESS-SIGN": GET_BALANCES}

    async def test_a_signed_call_without_a_key_says_so_rather_than_sending_garbage(self):
        client, seen = client_for(lambda r: ok([]), keyed=False)
        with pytest.raises(ValueError, match="has none"):
            await client.get_balances()
        assert seen == []


class TestSignedRequests:
    async def test_the_signature_covers_the_path_that_is_actually_sent(self):
        """The bug this guards: signing one string and sending another once a value is escaped."""
        client, seen = client_for(lambda r: ok([]))
        await client.get_open_orders("BTCUSDT")

        request = seen[0]
        path_with_query = str(request.url)[len(API):]
        assert path_with_query == "/v1/orders/open?symbol=BTCUSDT"
        assert request.headers["X-ACCESS-SIGN"] == sign(
            SECRET, request.headers["X-ACCESS-TIMESTAMP"], "GET", path_with_query)
        assert request.headers["X-ACCESS-KEY"] == KEY

    async def test_the_body_sent_is_the_body_signed(self):
        client, seen = client_for(lambda r: ok({"orderId": "1", "status": "NEW"}))
        await client.create_order("BTCUSDT", "BUY", "LIMIT", quantity=D("0.001"), price=D("30000.00"))

        request = seen[0]
        assert request.headers["X-ACCESS-SIGN"] == sign(
            SECRET, request.headers["X-ACCESS-TIMESTAMP"], "POST", "/v1/orders", request.content.decode())

    async def test_decimals_go_out_as_plain_strings(self):
        """A float in the JSON would be a float on the wire, which is the one thing forbidden."""
        client, seen = client_for(lambda r: ok({}))
        await client.create_order("BTCUSDT", "BUY", "LIMIT", quantity=D("0.00000001"), price=D("1E+2"))

        body = json.loads(seen[0].content)
        assert body["quantity"] == "0.00000001"
        assert body["price"] == "100"
        assert all(not isinstance(v, float) for v in body.values())

    async def test_an_order_uses_the_wire_names_and_leaves_unset_fields_out(self):
        client, seen = client_for(lambda r: ok({}))
        await client.create_order("BTCUSDT", "BUY", "MARKET", quote_order_qty=D("250"),
                                  client_order_id="c-1", self_trade_prevention_mode="EXPIRE_MAKER")

        assert json.loads(seen[0].content) == {
            "symbol": "BTCUSDT", "side": "BUY", "orderType": "MARKET", "quoteOrderQty": "250",
            "clientOrderId": "c-1", "selfTradePreventionMode": "EXPIRE_MAKER"}

    async def test_public_endpoints_are_not_signed(self):
        """A market data call carries no key: signing it would leak which account is watching."""
        client, seen = client_for(lambda r: ok({"symbols": []}))
        await client.get_markets_info()

        assert "X-ACCESS-SIGN" not in seen[0].headers
        assert "X-ACCESS-KEY" not in seen[0].headers

    async def test_an_order_is_addressed_by_either_id(self):
        client, seen = client_for(lambda r: ok({}))
        await client.cancel_order(order_id="o-1")
        await client.cancel_order(client_order_id="c-1")
        await client.amend_order(client_order_id="c-1", price=D("101"))

        assert [(r.method, r.url.path) for r in seen] == [
            ("DELETE", "/v1/orders/o-1"),
            ("DELETE", "/v1/orders/by-client-id/c-1"),
            ("PATCH", "/v1/orders/by-client-id/c-1")]
        with pytest.raises(ValueError):
            await client.cancel_order()

    async def test_cancel_all_sends_only_the_filters_given(self):
        client, seen = client_for(lambda r: ok({"count": 2}))
        await client.cancel_all_orders("BTCUSDT", side="BUY", scope="BOOK")

        assert str(seen[0].url)[len(API):] == "/v1/orders/open?symbol=BTCUSDT&side=BUY&scope=BOOK"


class TestDevHost:
    async def test_dev_calls_go_to_the_other_host_unsigned(self):
        """/dev lives on web-bff, and whoever asks for a first account has no key to sign with."""
        client, seen = client_for(lambda r: ok({"password": "p", "accounts": []}), keyed=False)
        await client.create_dev_accounts(3, "bot", "1000")

        assert str(seen[0].url) == f"{DEV}/dev/accounts"
        assert json.loads(seen[0].content) == {"count": 3, "label": "bot", "fund": "1000"}
        assert "X-ACCESS-SIGN" not in seen[0].headers

    async def test_a_missing_dev_endpoint_says_why(self):
        """Outside the dev profile these are a 404, which by itself reads like a typo in the path."""
        client, _ = client_for(lambda r: refused(404, 1004, "not found"), keyed=False)
        with pytest.raises(SpotApiError, match="dev profile"):
            await client.create_dev_accounts(1, "bot", "1")


class TestErrors:
    async def test_an_error_carries_the_http_status_and_the_exchanges_own_code(self):
        client, _ = client_for(lambda r: refused(400, 3001, "min notional"))
        with pytest.raises(SpotApiError) as caught:
            await client.create_order("BTCUSDT", "BUY", "LIMIT")

        assert (caught.value.status, caught.value.code, caught.value.message) == (400, 3001, "min notional")

    async def test_a_rate_limit_is_told_by_its_status(self):
        client, _ = client_for(lambda r: refused(429, 1003, "slow down"))
        with pytest.raises(SpotApiError) as caught:
            await client.get_balances()
        assert caught.value.status == 429

    async def test_a_market_that_never_traded_is_told_by_its_code(self):
        client, _ = client_for(lambda r: refused(404, 6002, "no market data"))
        with pytest.raises(SpotApiError) as caught:
            await client.get_ticker("BTCUSDT")
        assert caught.value.code == 6002

    async def test_a_body_that_is_not_json_still_raises_something_useful(self):
        """A proxy's HTML error page must not surface as a JSON decode traceback."""
        client, _ = client_for(lambda r: httpx.Response(502, text="<html>bad gateway</html>"))
        with pytest.raises(SpotApiError) as caught:
            await client.get_balances()
        assert caught.value.status == 502
        assert "bad gateway" in caught.value.message


class TestSession:
    async def test_it_opens_its_own_session_and_closes_it(self):
        client = await SpotAsyncClient.create(KEY, SECRET)
        assert not client._session.is_closed

        await client.close()
        assert client._session.is_closed

    async def test_a_request_after_close_opens_a_new_session_rather_than_failing(self):
        client = await SpotAsyncClient.create()
        await client.close()
        await client._init_session()
        assert not client._session.is_closed
        await client.close()


class FakeSocket:
    def __init__(self):
        self.sent: list[dict] = []

    async def send(self, message: str) -> None:
        self.sent.append(json.loads(message))


class TestSocket:
    def test_the_socket_shares_the_rest_origin(self):
        assert SpotAsyncClient(api_url="http://localhost:8080")._ws_url == "ws://localhost:8080/v1/ws"
        assert SpotAsyncClient(api_url="https://api.example.com/")._ws_url == "wss://api.example.com/v1/ws"

    async def test_authentication_signs_a_path_the_socket_never_calls(self, monkeypatch):
        monkeypatch.setattr(SpotAsyncClient, "_get_timestamp", staticmethod(lambda: TS))
        socket = FakeSocket()
        await SpotAsyncClient(KEY, SECRET)._ws_login(socket)

        assert socket.sent == [{"id": 1, "action": "auth", "apiKey": KEY, "timestamp": str(TS),
                                "signature": sign(SECRET, str(TS), "GET", "/v1/ws")}]

    async def test_private_frames_need_a_key(self):
        with pytest.raises(ValueError, match="has none"):
            await SpotAsyncClient()._ws_login(FakeSocket())

    async def test_a_subscription_carries_its_options_and_a_fresh_id(self):
        client, socket = SpotAsyncClient(), FakeSocket()
        await client.ws_subscribe(socket, "kline", "BTCUSDT", interval="1m")
        await client.ws_subscribe(socket, "depth", "BTCUSDT", grouping=None)

        assert socket.sent == [
            {"id": 1, "action": "subscribe", "channel": "kline", "symbol": "BTCUSDT", "interval": "1m"},
            {"id": 2, "action": "subscribe", "channel": "depth", "symbol": "BTCUSDT"}]
