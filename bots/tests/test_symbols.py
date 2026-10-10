from decimal import Decimal

import pytest

from bots.entities.market import Market
from bots.utils.money import D, ceil_to, floor_to, fmt

# BTCUSDT's real filters, as GET /v1/markets/info returns them.
PAYLOAD = {
    "symbols": [
        {
            "symbol": "BTCUSDT", "baseAsset": "BTC", "quoteAsset": "USDT", "status": "TRADING",
            "priceTick": "0.01", "qtyStep": "0.00001", "minQty": "0.00001", "maxQty": "9000",
            "minNotional": "10", "maxNotional": "1000000",
            "marketMinQty": "0.0001", "marketMaxQty": "100",
            "minQuoteQty": "10", "maxQuoteQty": "1000000",
            "baseDecimals": 8, "quoteDecimals": 2,
            "marketOrdersEnabled": True, "stopOrdersEnabled": True,
        },
        {
            "symbol": "NOMARKET", "baseAsset": "YYY", "quoteAsset": "USDT", "status": "TRADING",
            "priceTick": "0.01", "qtyStep": "1", "minQty": "1", "maxQty": "10",
            "minNotional": "10", "maxNotional": "100",
            "marketMinQty": "1", "marketMaxQty": "10",
            "minQuoteQty": "10", "maxQuoteQty": "100",
            "baseDecimals": 2, "quoteDecimals": 2,
            "marketOrdersEnabled": False, "stopOrdersEnabled": False,
        },
        {
            "symbol": "HALTED", "baseAsset": "XXX", "quoteAsset": "USDT", "status": "HALTED",
            "priceTick": "0.01", "qtyStep": "1", "minQty": "1", "maxQty": "10",
            "minNotional": "10", "maxNotional": "100",
            "marketMinQty": "1", "marketMaxQty": "10",
            "minQuoteQty": "10", "maxQuoteQty": "100",
            "baseDecimals": 2, "quoteDecimals": 2,
            "marketOrdersEnabled": False, "stopOrdersEnabled": False,
        },
    ]
}


@pytest.fixture
def btc():
    return Market.all_from_api(PAYLOAD)["BTCUSDT"]


class TestRounding:
    def test_a_buy_rounds_its_price_down_and_a_sell_rounds_up(self, btc):
        """Rounding must never turn a quote meant to rest into one that crosses."""
        assert btc.price(D("30000.007"), side="BUY") == D("30000.00")
        assert btc.price(D("30000.007"), side="SELL") == D("30000.01")

    def test_a_price_already_on_the_grid_is_left_alone(self, btc):
        assert btc.price(D("30000.01"), side="BUY") == D("30000.01")
        assert btc.price(D("30000.01"), side="SELL") == D("30000.01")

    def test_quantity_rounds_down_so_a_bot_never_buys_more_than_it_meant_to(self, btc):
        assert btc.quantity(D("0.123456789")) == D("0.12345")

    def test_a_size_too_small_to_be_legal_is_lifted_to_the_smallest_that_is(self, btc):
        """10 USDT at 30,000 is 0.00034 BTC once rounded up to the step."""
        quantity = btc.quantity(D("0.00001"), price=D("30000"))
        assert quantity == D("0.00034")
        assert quantity * D("30000") >= btc.min_notional
        assert btc.check(D("30000"), quantity) is None

    def test_quantity_is_clamped_to_the_filter_ceiling(self, btc):
        assert btc.quantity(D("100000")) == D("9000")

    def test_market_orders_have_their_own_bounds(self, btc):
        assert btc.quantity(D("0.00001"), market=True) == D("0.0001")
        assert btc.quantity(D("500"), market=True) == D("100")


class TestFilters:
    @pytest.mark.parametrize(
        "price, quantity, expected",
        [
            (D("30000.00"), D("0.001"), None),
            (D("30000.005"), D("0.001"), "not a multiple"),
            (D("30000.00"), D("0.0000123"), "not a multiple"),
            (D("30000.00"), D("0.0000001"), "below"),
            (D("30000.00"), D("0.0002"), "notional"),
            (D("0"), D("0.001"), "not positive"),
        ],
    )
    def test_says_what_the_exchange_would_object_to(self, btc, price, quantity, expected):
        problem = btc.check(price, quantity)
        assert (problem is None) if expected is None else (expected in problem)

    def test_a_market_that_is_not_trading_refuses_everything(self):
        halted = Market.all_from_api(PAYLOAD)["HALTED"]
        assert "HALTED" in halted.check(D("20.00"), D("1"))

    def test_a_market_order_where_none_are_allowed(self):
        """A trading market can still refuse market orders; halted ones refuse on status first."""
        no_market = Market.all_from_api(PAYLOAD)["NOMARKET"]
        assert "no market orders" in no_market.check(None, D("1"), market=True)
        assert no_market.check(D("20.00"), D("1")) is None


class TestMoney:
    def test_steps_round_the_way_they_say(self):
        assert floor_to(D("1.29"), D("0.1")) == D("1.2")
        assert ceil_to(D("1.21"), D("0.1")) == D("1.3")
        assert floor_to(D("-1.21"), D("0.1")) == D("-1.3")

    def test_a_zero_step_is_refused_rather_than_dividing_by_it(self):
        with pytest.raises(ValueError):
            floor_to(D("1"), D("0"))

    def test_the_wire_form_is_never_scientific_notation(self):
        """`str(Decimal("1E-8"))` is "1E-8", which the API reads as a malformed number."""
        assert fmt(D("0.00000001")) == "0.00000001"
        assert fmt(D("1E-8")) == "0.00000001"
        assert fmt(D("100")) == "100"
        assert fmt(D("1E+2")) == "100"
        assert fmt(D("30000.10")) == "30000.1"

    def test_nothing_here_is_a_float(self, btc):
        """The rule the linter cannot check: money stays Decimal end to end."""
        assert isinstance(btc.price(D("30000.007"), side="BUY"), Decimal)
        assert isinstance(btc.quantity(D("0.123456789")), Decimal)
        assert not isinstance(btc.price_tick, float)
