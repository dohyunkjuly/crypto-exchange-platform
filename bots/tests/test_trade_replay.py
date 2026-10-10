import asyncio
import random
import time

import pytest

from bots.clients.spot_client import SpotApiError
from bots.controllers.trade_replay_controller import SimulatorConfig
from bots.controllers.trade_replay_controller import (
    TradeReplayController as Controller,
)
from bots.entities.balance import Balance
from bots.entities.market import Market
from bots.entities.order import Order, OrderStatus, Side, TimeInForce
from bots.utils.money import D

MARKET = Market.from_api({
    "symbol": "BTCUSDT", "baseAsset": "BTC", "quoteAsset": "USDT", "status": "TRADING",
    "priceTick": "0.01", "qtyStep": "0.00001", "minQty": "0.00001", "maxQty": "9000",
    "minNotional": "10", "maxNotional": "10000000", "marketMinQty": "0.0002", "marketMaxQty": "100",
    "minQuoteQty": "10", "maxQuoteQty": "10000000", "marketOrdersEnabled": True, "stopOrdersEnabled": True})
CONFIG = SimulatorConfig()
PRICE = D("80000")


def resting(order_id, side, price, held_since=0.0):
    """Held since long ago unless said otherwise: old enough for the overlap sweep to touch."""
    return Order(order_id, "BTCUSDT", side, D(price), D("0.001"), OrderStatus.NEW, held_since)


def listed(order_id, side="BUY", price="100", remaining="1", status="NEW", symbol="BTCUSDT"):
    """An order as `GET /v1/orders/open` and the `order` inside an `orderUpdate` spell it."""
    return {"orderId": order_id, "symbol": symbol, "side": side, "price": price,
            "remainingQuantity": remaining, "status": status}


def order_frame(order_id, status, *, remaining=None, full=None):
    return {"type": "orderUpdate", "data": {"orderId": order_id, "status": status,
                                            "remainingQuantity": remaining, "order": full}}


def balance_frame(asset, free, locked, version):
    return {"type": "balanceChanged",
            "data": {"asset": asset, "free": free, "locked": locked, "version": version}}


class FakeSpot:
    calls: list = []   # shared, so the ORDER of calls across the two accounts is recorded

    def __init__(self, name):
        self.name = name
        self.open_orders: list = []
        self.balances: list = []
        self.cancelled: list = []
        self.cancelled_all: list = []
        self.refuse_orders = self.refuse_cancels = False
        self.fail_reads = 0

    async def create_order(self, symbol, side, order_type, *, quantity, price, time_in_force):
        if self.refuse_orders:
            raise SpotApiError(400, 3001, "refused", "/v1/orders")
        FakeSpot.calls.append((self.name, side, time_in_force, price))
        return {"orderId": f"{self.name}-{len(FakeSpot.calls)}", "status": "NEW"}

    async def cancel_order(self, order_id):
        if self.refuse_cancels:
            raise SpotApiError(409, 5016, "not open", "/v1/orders")
        self.cancelled.append(order_id)

    async def get_open_orders(self, symbol=None):
        if self.fail_reads:
            self.fail_reads -= 1
            raise SpotApiError(401, 2103, "stale", "/v1/orders/open")
        return list(self.open_orders)

    async def get_balances(self):
        return list(self.balances)

    async def cancel_all_orders(self, symbol=None, **_):
        self.cancelled_all.append(symbol)


def controller(bids_base="1", asks_base="1", **config):
    """Both accounts hold 80,000 USDT, and as much BTC as said."""
    FakeSpot.calls = []
    bids, asks = FakeSpot("bids"), FakeSpot("asks")
    made = Controller("BTCUSDT", bids, asks, feed=None, config=SimulatorConfig(**config))
    made._market, made._price = MARKET, PRICE
    for account, base in ((bids, bids_base), (asks, asks_base)):
        made._balances[account] = {"BTC": Balance("BTC", D(base), D("0"), 1),
                                   "USDT": Balance("USDT", D("80000"), D("0"), 1)}
    return made, bids, asks


def hold(made, account, *orders):
    made._orders[account] = {order.order_id: order for order in orders}


class TestSimulatorConfig:
    TABLE = {"order_notional": "200", "trade_notional_min": "10", "replay_every_trade": False,
             "trade_interval_seconds": "2.5", "symbols": ["BTCUSDT", "ETHUSDT"],
             "BTCUSDT": {"order_notional": "500", "trade_notional_max": "300"}}

    def test_a_markets_own_table_covers_the_shared_one_and_only_where_it_says(self):
        btc = SimulatorConfig.from_table(self.TABLE, "BTCUSDT")
        assert (btc.order_notional, btc.trade_notional_min, btc.trade_notional_max) == (
            D("500"), D("10"), D("300"))

    def test_a_market_without_its_own_table_gets_the_shared_values(self):
        eth = SimulatorConfig.from_table(self.TABLE, "ETHUSDT")
        assert (eth.order_notional, eth.trade_notional_min, eth.trade_notional_max) == (
            D("200"), D("10"), D("100"))

    def test_when_to_trade_is_read_as_a_flag_and_a_decimal(self):
        config = SimulatorConfig.from_table(self.TABLE, "ETHUSDT")
        assert (config.replay_every_trade, config.trade_interval_seconds) == (False, D("2.5"))
        assert SimulatorConfig.from_table({}).replay_every_trade is True


class TestWhenToTrade:
    async def test_on_every_print_the_trade_is_queued(self):
        made, _, _ = controller()
        await made._on_feed_trade({"p": "80100", "q": "1", "m": True})
        assert (made._price, made._backlog.qsize()) == (D("80100"), 1)

    async def test_on_an_interval_a_print_moves_the_price_and_queues_nothing(self):
        made, _, _ = controller(replay_every_trade=False)
        await made._on_feed_trade({"p": "80100", "q": "1", "m": True})
        assert (made._price, made._backlog.qsize()) == (D("80100"), 0)

    async def test_on_an_interval_a_trade_happens_at_the_last_price_heard(self):
        made, bids, asks = controller(replay_every_trade=False, trade_interval_seconds="0.01")
        await made._on_feed_trade({"p": "80100", "q": "1", "m": True})
        job = asyncio.create_task(made._replay_on_interval())
        await asyncio.sleep(0.05)
        job.cancel()
        assert FakeSpot.calls, "nothing traded"
        assert {price for _, _, _, price in FakeSpot.calls} == {D("80100")}


class TestReplayOrder:
    def test_the_feeds_m_means_the_buyer_made_so_the_taker_sold(self):
        rng = random.Random(1)
        buyer_made = Controller._replay_order({"p": "80000", "q": "1", "m": True}, MARKET, CONFIG, rng)
        seller_made = Controller._replay_order({"p": "80000", "q": "1", "m": False}, MARKET, CONFIG, rng)
        assert (buyer_made[0], seller_made[0]) == (Side.SELL, Side.BUY)

    def test_a_price_finer_than_our_tick_goes_onto_it(self):
        _, price, _ = Controller._replay_order({"p": "80000.12345678", "q": "1", "m": False}, MARKET, CONFIG,
                                               random.Random(1))
        assert price % MARKET.price_tick == 0

    def test_the_size_is_ours_not_the_feeds_small_and_always_a_legal_order(self):
        """Both sides of a replay are ours and both pay the fee, so a whale's print is replayed small."""
        rng = random.Random(3)
        for _ in range(300):
            _, price, quantity = Controller._replay_order({"p": "80000", "q": "5000", "m": False}, MARKET,
                                                          CONFIG, rng)
            assert CONFIG.trade_notional_min - 1 <= price * quantity <= CONFIG.trade_notional_max
            assert MARKET.check(price, quantity) is None


class TestRandomRestingOrder:
    def orders(self, side, n=500):
        rng = random.Random(7)
        return [Controller._random_resting_order(side, PRICE, MARKET, CONFIG, rng) for _ in range(n)]

    def test_bids_rest_below_the_price_and_asks_above_it_never_across(self):
        assert all(price < PRICE for price, _ in self.orders(Side.BUY))
        assert all(price > PRICE for price, _ in self.orders(Side.SELL))

    def test_every_one_is_an_order_the_exchange_would_accept(self):
        for price, quantity in self.orders(Side.BUY) + self.orders(Side.SELL):
            assert MARKET.check(price, quantity) is None

    def test_they_stay_inside_the_configured_distance_and_most_sit_near_the_price(self):
        away = [abs(price - PRICE) / PRICE * 10_000 for price, _ in self.orders(Side.SELL)]
        assert CONFIG.liquidity_min_bps - 1 <= min(away) and max(away) <= CONFIG.liquidity_max_bps + 1
        assert sum(1 for bps in away if bps < 100) > len(away) / 2


class TestOverlapping:
    ORDERS = [resting("bid-far", Side.BUY, "79000"), resting("bid-at", Side.BUY, "79990"),
              resting("bid-through", Side.BUY, "79995"), resting("ask-far", Side.SELL, "81000"),
              resting("ask-through", Side.SELL, "79980")]

    def test_the_price_fell_onto_bids_at_or_above_it_and_onto_no_bid_below(self):
        crossed = [o.order_id for o in Controller._overlapping(self.ORDERS, D("79990")) if o.side == Side.BUY]
        assert crossed == ["bid-at", "bid-through"]

    def test_an_ask_at_or_below_the_price_overlaps_whoever_left_it_there(self):
        asks = [o.order_id for o in Controller._overlapping(self.ORDERS, D("79990")) if o.side == Side.SELL]
        assert asks == ["ask-through"]

    def test_a_price_inside_the_spread_overlaps_nothing(self):
        inside = [resting("b", Side.BUY, "79000"), resting("a", Side.SELL, "81000")]
        assert Controller._overlapping(inside, PRICE) == []


class TestAccountState:
    async def test_an_account_is_not_ready_until_its_orders_and_balances_have_been_read(self):
        made, bids, _ = controller()
        assert not made._ready[bids].is_set()
        await made._resync(bids)
        assert made._ready[bids].is_set()

    async def test_a_resync_forgets_what_the_exchange_no_longer_lists(self):
        made, bids, _ = controller()
        hold(made, bids, resting("a", Side.BUY, "100"), resting("b", Side.BUY, "100"))
        bids.open_orders = [listed("b")]
        await made._resync(bids)

        assert [o.order_id for o in made._open_orders(bids)] == ["b"]

    async def test_an_accepted_order_arrives_whole_and_is_held_on_its_own_account_only(self):
        made, bids, asks = controller()
        await made._on_user_frame(bids, order_frame("a", "NEW", full=listed("a", price="99.5")))

        assert [o.price for o in made._open_orders(bids, Side.BUY)] == [D("99.5")]
        assert made._open_orders(asks) == []

    async def test_a_fill_carries_no_order_only_what_is_left(self):
        made, bids, _ = controller()
        hold(made, bids, resting("a", Side.BUY, "100"))
        await made._on_user_frame(bids, order_frame("a", "PARTIALLY_FILLED", remaining="0.0004"))

        assert made._open_orders(bids)[0].remaining == D("0.0004")

    @pytest.mark.parametrize("status", ["FILLED", "CANCELED", "EXPIRED", "REJECTED"])
    async def test_an_order_that_has_ended_is_no_longer_open(self, status):
        made, bids, _ = controller()
        hold(made, bids, resting("a", Side.BUY, "100"))
        await made._on_user_frame(bids, order_frame("a", status))

        assert made._open_orders(bids) == []

    async def test_a_placed_order_is_open_from_the_202_not_from_the_frame_that_follows(self):
        """Counting resting orders between the two would place the same one twice."""
        made, bids, _ = controller()
        await made._place(bids, Side.BUY, D("100"), D("1"), TimeInForce.GTC)

        assert [(o.price, o.remaining) for o in made._open_orders(bids, Side.BUY)] == [(D("100"), D("1"))]

    async def test_a_fill_that_beat_the_202_does_not_leave_the_order_held_as_open(self):
        made, bids, _ = controller()
        await made._on_user_frame(bids, order_frame("bids-1", "FILLED"))
        await made._place(bids, Side.BUY, D("100"), D("1"), TimeInForce.GTC)

        assert made._open_orders(bids) == []

    async def test_a_cancel_the_exchange_refuses_is_forgotten_too_and_logged(self):
        """It refuses only what is not open: holding it would mean cancelling it forever."""
        made, bids, _ = controller()
        hold(made, bids, resting("a", Side.BUY, "100"))
        bids.refuse_cancels = True
        await made._cancel(bids, made._open_orders(bids)[0])

        assert made._open_orders(bids) == [] and made._failed == {"code 5016"}

    async def test_the_snapshot_says_available_the_frame_says_free_and_an_older_frame_is_dropped(self):
        made, bids, _ = controller()
        bids.balances = [{"asset": "USDT", "available": "900", "locked": "100", "version": 5}]
        await made._resync(bids)
        assert made._total(bids, "USDT") == D("1000")

        await made._on_user_frame(bids, balance_frame("USDT", "850", "100", 6))
        await made._on_user_frame(bids, balance_frame("USDT", "1", "0", 4))
        assert made._total(bids, "USDT") == D("950")

    async def test_an_asset_never_seen_is_zero_not_an_error(self):
        made, bids, _ = controller()
        assert made._total(bids, "DOGE") == 0


class TestReplay:
    async def test_whoever_holds_more_of_the_base_asset_sells_it_to_the_other(self):
        made, _, _ = controller(bids_base="3", asks_base="1")
        await made._replay({"p": "80000", "q": "1", "m": False})
        assert [call[:2] for call in FakeSpot.calls] == [("bids", Side.SELL), ("asks", Side.BUY)]

        made, _, _ = controller(bids_base="1", asks_base="3")
        await made._replay({"p": "80000", "q": "1", "m": False})
        assert [call[:2] for call in FakeSpot.calls] == [("asks", Side.SELL), ("bids", Side.BUY)]

    async def test_the_maker_rests_first_and_the_feeds_side_decides_which_of_the_two_that_is(self):
        """The taker's order has to find the maker's on the book, so the maker's 202 comes first."""
        made, _, _ = controller(bids_base="1", asks_base="3")
        await made._replay({"p": "80000", "q": "1", "m": True})     # the taker sold

        assert [call[:3] for call in FakeSpot.calls] == [
            ("bids", Side.BUY, TimeInForce.GTC), ("asks", Side.SELL, TimeInForce.IOC)]
        assert {price for *_, price in FakeSpot.calls} == {D("80000")}

    async def test_what_the_price_moved_onto_is_cancelled_before_the_trade_is_placed(self):
        made, bids, asks = controller(bids_base="1", asks_base="3")
        hold(made, bids, resting("low", Side.BUY, "79000"), resting("hit", Side.BUY, "80010"))
        hold(made, asks, resting("high", Side.SELL, "81000"))
        await made._replay({"p": "80000", "q": "1", "m": False})

        assert bids.cancelled == ["hit"] and asks.cancelled == []

    async def test_a_maker_order_nobody_took_is_swept_by_the_next_replay_whichever_account_left_it(self):
        made, bids, _ = controller(bids_base="3", asks_base="1")
        hold(made, bids, resting("stray-ask", Side.SELL, "79990"))
        await made._replay({"p": "80000", "q": "1", "m": False})

        assert bids.cancelled == ["stray-ask"]

    async def test_an_order_held_for_only_a_moment_is_not_swept_its_fill_may_still_be_on_its_way(self):
        """The replay before this one rested it, and it has most likely been taken."""
        made, bids, _ = controller(bids_base="3", asks_base="1")
        hold(made, bids, resting("just-rested", Side.SELL, "79990", held_since=time.monotonic()))
        await made._replay({"p": "80000", "q": "1", "m": False})

        assert bids.cancelled == []

    async def test_a_refused_order_is_logged_once_and_the_replaying_goes_on(self):
        made, bids, _ = controller(bids_base="1", asks_base="3")
        bids.refuse_orders = True

        assert await made._counting_failures(made._replay({"p": "80000", "q": "1", "m": True})) is False
        assert await made._counting_failures(made._replay({"p": "80000", "q": "1", "m": True})) is False
        assert made._failed == {"code 3001"}

    async def test_a_full_backlog_drops_the_oldest_trade_so_the_newest_price_is_the_one_replayed(self):
        made, _, _ = controller(max_backlog=2)
        for price in ("1", "2", "3"):
            await made._on_feed_trade({"p": price, "q": "1", "m": False})

        assert [made._backlog.get_nowait()["p"] for _ in range(2)] == ["2", "3"]
        assert made._price == D("3")


class TestLiquidity:
    async def test_a_side_under_its_target_is_topped_up_a_few_at_a_time_on_its_own_side_only(self):
        made, bids, _ = controller(resting_orders_per_side=8)
        await made._top_up(bids, Side.BUY)
        assert len(made._open_orders(bids)) == made.requests_at_once
        await made._top_up(bids, Side.BUY)
        await made._top_up(bids, Side.BUY)

        held = made._open_orders(bids)
        assert len(held) == 8 and all(o.side == Side.BUY and o.price < PRICE for o in held)

    async def test_an_order_the_price_has_left_far_behind_is_replaced_by_one_that_is_not(self):
        made, bids, _ = controller(resting_orders_per_side=2)
        hold(made, bids, resting("near", Side.BUY, "79900"), resting("gone", Side.BUY, "70000"))
        await made._top_up(bids, Side.BUY)

        assert bids.cancelled == ["gone"]
        held = made._open_orders(bids)
        assert len(held) == 2 and all(o.price > D("75000") for o in held)


def test_the_config_table_overrides_the_defaults_and_money_is_read_as_decimals():
    config = SimulatorConfig.from_table(
        {"resting_orders_per_side": 10, "trade_notional_max": "55.5", "symbols": []})

    assert config.resting_orders_per_side == 10 and config.trade_notional_max == D("55.5")
    assert config.order_notional == SimulatorConfig().order_notional


@pytest.mark.parametrize("failing", ["bids", "asks"])
async def test_it_clears_both_accounts_in_its_own_market_and_tries_again_when_one_fails(failing, monkeypatch):
    made, bids, asks = controller()
    bids.open_orders, asks.open_orders = [listed("a")], [listed("b")]
    (bids if failing == "bids" else asks).fail_reads = 1
    real_sleep = asyncio.sleep
    monkeypatch.setattr(asyncio, "sleep", lambda _seconds: real_sleep(0))

    assert await made._cancel_everything_of_both() == 2
    assert bids.cancelled_all == ["BTCUSDT"] and asks.cancelled_all == ["BTCUSDT"]
