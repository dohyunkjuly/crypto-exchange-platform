"""Replays the reference feed's trades onto one of our markets, between two accounts, over a book they keep.

Two accounts a market, one for each side of the book:

    bids account    keeps `resting_orders_per_side` random buy orders below the price
    asks account    keeps as many random sell orders above it

and three jobs:

    liquidity   tops each side back up, and cancels what the price has left far behind.
    overlap     the price fell onto resting bids, or rose onto resting asks: they are cancelled,
                not left to be hit. The book follows the feed's price; it does not resist it.
    replay      for every feed trade, the two accounts trade a small random amount with each
                other at that price: one rests an order there, the other takes it. Whichever
                holds more of the base asset sells, so neither runs out of either asset.
                The feed's side of the trade only decides which of the two is the taker.

What each account has resting, and holds of each asset, is kept in memory: read once over REST,
then kept current by the account's own socket. Asking the exchange before every replay would cost
more requests than the replay itself.

Replays go one at a time. A second one in flight would cancel, as overlap, the order the first
had just rested to be taken. When the feed prints faster than that, the oldest waiting trades are
dropped: the book should be where the price is, not where it was.
"""

import asyncio
import random
import time
from collections import deque
from dataclasses import dataclass
from decimal import Decimal
from functools import partial

import httpx

from ..clients.reference_feed_client import ReferenceFeedClient
from ..clients.spot_client import SpotApiError, SpotAsyncClient
from ..entities.balance import Balance
from ..entities.market import Market
from ..entities.order import Order, OrderStatus, OrderType, Side, TimeInForce
from ..utils.money import D, fmt
from .controller_base import ControllerBase

__all__ = ["TradeReplayController", "SimulatorConfig"]


@dataclass(frozen=True, slots=True)
class SimulatorConfig:
    """`[trade_replay]` in config/bots.toml, and under it `[trade_replay.BTCUSDT]`
    for what one market does differently. A key both leave out keeps the value here.

    Sizes are in the market's quote asset, which is why they are settable per market: 200 is a
    sensible order on a USDT market and a fortune on one quoted in BTC.
    """

    resting_orders_per_side: int = 150
    # How far from the price a resting order is placed. Most land near the low end.
    liquidity_min_bps: Decimal = D("5")
    liquidity_max_bps: Decimal = D("300")
    # A resting order's typical size, in the quote asset.
    order_notional: Decimal = D("200")
    # A replayed trade's size, in the quote asset: random between the two, and small, because
    # both sides of it are ours and both pay the fee.
    trade_notional_min: Decimal = D("10")
    trade_notional_max: Decimal = D("100")
    # Trades waiting to be replayed. Past this the oldest is dropped.
    max_backlog: int = 100
    # True: a trade of ours for every feed print. False: one every `trade_interval_seconds`,
    # at whatever the feed's price is then -- a quiet market, or a cheaper run.
    replay_every_trade: bool = True
    trade_interval_seconds: Decimal = D("1")

    @classmethod
    def from_table(cls, table: dict, symbol: str | None = None) -> "SimulatorConfig":
        """The table's values, with the market's own sub-table over them."""
        merged = {**table, **table.get(symbol, {})} if symbol else table
        whole = {"resting_orders_per_side", "max_backlog"}
        flags = {"replay_every_trade"}
        fields = {name: (int(merged[name]) if name in whole else bool(merged[name]) if name in flags
                         else D(str(merged[name])))
                  for name in cls.__dataclass_fields__ if name in merged}
        return cls(**fields)


class TradeReplayController(ControllerBase):

    liquidity_seconds = 0.5
    # The socket heals itself on a reconnect. This is for what it got wrong without dropping.
    resync_seconds = 30.0
    # Orders one account places or cancels at once.
    requests_at_once = 5
    # A resting order this many times further out than the furthest one placed has been left
    # behind by the price, and is replaced by one that is not.
    left_behind_factor = 2
    # An order younger than this is not swept as overlap. The maker order of the replay before
    # is still held as open until the frame for its fill arrives, and cancelling it would be a
    # command spent on an order that is already gone.
    settle_seconds = 1.0

    def __init__(self, symbol: str, bids: SpotAsyncClient, asks: SpotAsyncClient, feed: ReferenceFeedClient,
                 config: SimulatorConfig | None = None):
        super().__init__(f"trade_replay_{symbol}")
        self.symbol = symbol
        self.config = config or SimulatorConfig()
        self._bids = bids
        self._asks = asks
        self._feed = feed
        self._market: Market | None = None
        self._price: Decimal | None = None
        self._backlog: asyncio.Queue[dict] = asyncio.Queue(maxsize=self.config.max_backlog)
        self._rng = random.Random(symbol)
        # Each kind of refusal or failure is logged the first time; a flood of the same one is not.
        self._failed: set[str] = set()
        # Each account's state in this market, by its client.
        self._orders: dict[SpotAsyncClient, dict[str, Order]] = {bids: {}, asks: {}}
        self._balances: dict[SpotAsyncClient, dict[str, Balance]] = {bids: {}, asks: {}}
        # Set once an account's orders and balances have been read for the first time.
        self._ready = {bids: asyncio.Event(), asks: asyncio.Event()}
        # The socket can beat the 202: an order that filled at once has its last frame arrive
        # before `create_order` returns, and must not be held as open after it.
        self._closed: deque[str] = deque(maxlen=2000)

    # ------------------------------------------------------------------ life

    async def _on_start(self) -> None:
        self._market = Market.all_from_api(await self._bids.get_markets_info()).get(self.symbol)
        if self._market is None or not self._market.trading:
            raise ValueError(f"{self.symbol} is not a market this exchange trades")
        self._price = D((await self._feed.get_ticker_price(self.symbol))["price"])
        cleared = await self._cancel_everything_of_both()
        self.logger.info("%s: price %s; cleared %d orders left from before",
                         self.symbol, fmt(self._price), cleared)

    async def _run(self) -> None:
        sockets = [asyncio.create_task(self._consume(
            account.ws_user_data, partial(self._on_user_frame, account), partial(self._resync, account)))
            for account in (self._bids, self._asks)]
        ready = asyncio.ensure_future(asyncio.gather(*(event.wait() for event in self._ready.values())))
        stopped = asyncio.create_task(self._stopping.wait())
        jobs = [ready, stopped]
        try:
            # Nothing is decided on an account whose orders and balances have not been read yet.
            await asyncio.wait({ready, stopped}, return_when=asyncio.FIRST_COMPLETED)
            if not self.stopping:
                replay = (self._replay_trades() if self.config.replay_every_trade
                          else self._replay_on_interval())
                jobs += [asyncio.create_task(job) for job in (
                    self._consume(lambda: self._feed.ws_trade(self.symbol), self._on_feed_trade),
                    replay, self._keep_liquidity(), self._keep_in_sync())]
                await stopped
        finally:
            for job in jobs + sockets:
                job.cancel()
            await asyncio.gather(*jobs, *sockets, return_exceptions=True)

    async def _on_stop(self) -> None:
        cancelled = await self._cancel_everything_of_both()
        self.logger.info("%s: cancelled %d orders on the way out", self.symbol, cancelled)

    async def _cancel_everything_of_both(self, attempts: int = 3) -> int:
        """This market only. Tried again on failure: this is what runs on the way out, and an
        exchange that is struggling is exactly when orders must not be left behind."""
        cancelled = 0
        for account in (self._bids, self._asks):
            for attempt in range(attempts):
                try:
                    cancelled += await self._cancel_everything(account, self.symbol)
                    break
                except (SpotApiError, httpx.HTTPError) as e:
                    if attempt == attempts - 1:
                        self.logger.error("%s: an account could not be cleared: %s", self.symbol, e)
                    await asyncio.sleep(1.0)
        return cancelled

    # ------------------------------------------------------------------ each account's state

    async def _resync(self, account: SpotAsyncClient) -> None:
        """Reads both lists again. Runs on every (re)connection of the account's socket, since
        nothing is replayed after one, and on a timer."""
        orders, balances = await asyncio.gather(account.get_open_orders(self.symbol), account.get_balances())
        fresh = {order.order_id: order for order in map(Order.from_api, orders)}
        for order_id, order in fresh.items():
            if order_id in self._orders[account]:
                order.held_since = self._orders[account][order_id].held_since
        self._orders[account] = fresh
        for balance in map(Balance.from_api, balances):
            self._keep_balance(account, balance)
        self._ready[account].set()

    async def _keep_in_sync(self) -> None:
        while True:
            await asyncio.sleep(self.resync_seconds)
            await self._counting_failures(asyncio.gather(self._resync(self._bids), self._resync(self._asks)))

    async def _on_user_frame(self, account: SpotAsyncClient, frame: dict) -> None:
        """Frames are whole state (docs/reference/sockets.md): one replaces what is held."""
        data = frame.get("data")
        if frame.get("type") == "balanceChanged":
            self._keep_balance(account, Balance.from_api(data))
        elif frame.get("type") == "orderUpdate":
            held = self._orders[account]
            if not OrderStatus(data["status"]).is_open:
                held.pop(data["orderId"], None)
                self._closed.append(data["orderId"])
            elif data.get("order") is not None and data["order"]["symbol"] == self.symbol:
                order = Order.from_api(data["order"])
                if order.order_id in held:
                    order.held_since = held[order.order_id].held_since
                held[order.order_id] = order
            elif data["orderId"] in held:
                # A fill carries no order, only what changed.
                held[data["orderId"]].status = OrderStatus(data["status"])
                if data.get("remainingQuantity") is not None:
                    held[data["orderId"]].remaining = D(data["remainingQuantity"])

    def _keep_balance(self, account: SpotAsyncClient, balance: Balance) -> None:
        """Frames can arrive out of order; the exchange's version says which is newer."""
        held = self._balances[account].get(balance.asset)
        if held is None or balance.version > held.version:
            self._balances[account][balance.asset] = balance

    def _open_orders(self, account: SpotAsyncClient, side: Side | None = None) -> list[Order]:
        return [order for order in self._orders[account].values() if side is None or order.side == side]

    def _total(self, account: SpotAsyncClient, asset: str) -> Decimal:
        held = self._balances[account].get(asset)
        return held.total if held else D("0")

    async def _place(self, account: SpotAsyncClient, side: Side, price: Decimal, quantity: Decimal,
                     time_in_force: TimeInForce) -> None:
        """Held as open from the 202, not from the frame that follows: counting resting orders
        between the two would place the same one twice."""
        placed = await account.create_order(self.symbol, side, OrderType.LIMIT, quantity=quantity,
                                            price=price, time_in_force=time_in_force)
        if OrderStatus(placed["status"]).is_open and placed["orderId"] not in self._closed:
            self._orders[account].setdefault(placed["orderId"], Order(
                placed["orderId"], self.symbol, side, price, quantity, OrderStatus(placed["status"])))

    async def _cancel(self, account: SpotAsyncClient, order: Order) -> None:
        """Forgotten at the 202, and on a refusal too: the exchange refuses to cancel only what
        is not open, and holding it would mean cancelling it forever."""
        try:
            await account.cancel_order(order.order_id)
        except SpotApiError as e:
            self._failure(e)
        except httpx.HTTPError as e:
            self._failure(e)
            return
        self._orders[account].pop(order.order_id, None)

    # ------------------------------------------------------------------ replay

    async def _on_feed_trade(self, trade: dict) -> None:
        # {"e":"trade","s":"BTCUSDT","p":"63250.10","q":"0.0042","m":true,...}
        self._price = D(trade["p"])
        if not self.config.replay_every_trade:
            return  # the price is all that is wanted; _replay_on_interval trades at it
        if self._backlog.full():
            self._backlog.get_nowait()
        self._backlog.put_nowait(trade)

    async def _replay_trades(self) -> None:
        while True:
            await self._counting_failures(self._replay(await self._backlog.get()))

    async def _replay_on_interval(self) -> None:
        """A trade of ours every `trade_interval_seconds` at the last price heard, the taker's
        side a coin toss: The feed's prints move the price and nothing else."""
        while True:
            await asyncio.sleep(float(self.config.trade_interval_seconds))
            trade = {"p": str(self._price), "m": self._rng.random() < 0.5}
            await self._counting_failures(self._replay(trade))

    async def _replay(self, trade: dict) -> None:
        order = self._replay_order(trade, self._market, self.config, self._rng)
        if order is None:
            return
        taker_side, price, quantity = order
        await self._cancel_overlapping(price)

        seller, buyer = self._seller_and_buyer(price)
        maker, maker_side = (buyer, Side.BUY) if taker_side == Side.SELL else (seller, Side.SELL)
        taker = seller if maker is buyer else buyer
        # The maker's 202 is awaited before the taker is sent: both go to one partition in
        # the order the API published them, so the engine has the maker when the taker arrives.
        await self._place(maker, maker_side, price, quantity, TimeInForce.GTC)
        await self._place(taker, taker_side, price, quantity, TimeInForce.IOC)

    async def _cancel_overlapping(self, price: Decimal) -> None:
        """Both accounts, both sides: a maker order from an earlier replay that was not taken is
        resting on the side its account does not normally quote."""
        settled = time.monotonic() - self.settle_seconds
        for account in (self._bids, self._asks):
            crossed = [order for order in self._overlapping(self._open_orders(account), price)
                       if order.held_since <= settled]
            for start in range(0, len(crossed), self.requests_at_once):
                await asyncio.gather(*(self._cancel(account, order)
                                       for order in crossed[start:start + self.requests_at_once]))

    def _seller_and_buyer(self, price: Decimal) -> tuple[SpotAsyncClient, SpotAsyncClient]:
        """Whichever holds more of the base asset sells it to the other."""
        if self._base_share(self._bids, price) > self._base_share(self._asks, price):
            return self._bids, self._asks
        return self._asks, self._bids

    def _base_share(self, account: SpotAsyncClient, price: Decimal) -> Decimal:
        """How much of the account's worth in this market is the base asset, from 0 to 1."""
        base_worth = self._total(account, self._market.base) * price
        worth = base_worth + self._total(account, self._market.quote)
        return base_worth / worth if worth > 0 else D("0.5")

    @staticmethod
    def _replay_order(trade: dict, market: Market, config: SimulatorConfig,
                      rng: random.Random) -> tuple[Side, Decimal, Decimal] | None:
        """A feed trade as `(taker_side, price, quantity)` for our market, or None if it cannot be one.

        The feed's `m` is "the buyer was the maker", so the taker sold. The price goes onto our tick.
        The quantity is ours, not the feed's: a random small notional, onto our step.
        """
        taker_side = Side.SELL if trade["m"] else Side.BUY
        maker_side = Side.BUY if taker_side == Side.SELL else Side.SELL
        price = market.price(D(trade["p"]), side=maker_side)
        if price <= 0:
            return None
        spread = config.trade_notional_max - config.trade_notional_min
        notional = config.trade_notional_min + spread * D(rng.random())
        quantity = market.quantity(notional / price, price=price)
        if market.check(price, quantity) is not None:
            return None
        return taker_side, price, quantity

    @staticmethod
    def _overlapping(orders: list[Order], price: Decimal) -> list[Order]:
        """The resting orders a trade at `price` would run into: bids at or above it, asks at or below."""
        return [order for order in orders if order.price is not None
                and (order.price >= price if order.side == Side.BUY else order.price <= price)]

    # ------------------------------------------------------------------ liquidity

    async def _keep_liquidity(self) -> None:
        while True:
            await asyncio.gather(self._top_up(self._bids, Side.BUY), self._top_up(self._asks, Side.SELL))
            await asyncio.sleep(self.liquidity_seconds)

    async def _top_up(self, account: SpotAsyncClient, side: Side) -> None:
        """A few at a time, so one pass is short and the next one sees a fresher price."""
        resting = self._open_orders(account, side)
        too_far = self._price * self.config.liquidity_max_bps * self.left_behind_factor / 10_000
        left_behind = [o for o in resting if abs(o.price - self._price) > too_far][:self.requests_at_once]
        missing = self.config.resting_orders_per_side - len(resting) + len(left_behind)
        await asyncio.gather(*(self._cancel(account, order) for order in left_behind),
                             *(self._rest_one(account, side)
                               for _ in range(max(0, min(missing, self.requests_at_once)))))

    async def _rest_one(self, account: SpotAsyncClient, side: Side) -> None:
        order = self._random_resting_order(side, self._price, self._market, self.config, self._rng)
        if order is not None:
            await self._counting_failures(self._place(account, side, *order, TimeInForce.GTC))

    @staticmethod
    def _random_resting_order(side: Side, reference: Decimal, market: Market, config: SimulatorConfig,
                              rng: random.Random) -> tuple[Decimal, Decimal] | None:
        """`(price, quantity)` to rest on `side` of `reference`: a bid below, an ask above, never across."""
        # Squared, so most orders sit close to the price and the book thins out away from it.
        spread_bps = config.liquidity_max_bps - config.liquidity_min_bps
        away = (config.liquidity_min_bps + spread_bps * D(rng.random() ** 2)) / 10_000
        price = market.price(reference * (1 - away if side == Side.BUY else 1 + away), side=side)
        if price <= 0:
            return None
        quantity = market.quantity(config.order_notional * D(rng.uniform(0.25, 2.5)) / price, price=price)
        if market.check(price, quantity) is not None:
            return None
        return price, quantity

    async def _counting_failures(self, request) -> bool:
        """A refused or failed request is logged, not the end of the job."""
        try:
            await request
            return True
        except (SpotApiError, httpx.HTTPError) as e:
            self._failure(e)
        return False

    def _failure(self, error: Exception) -> None:
        """Once per kind: the first refusal says what is wrong, the thousandth says nothing new."""
        kind = f"code {error.code}" if isinstance(error, SpotApiError) else error.__class__.__name__
        if kind not in self._failed:
            self._failed.add(kind)
            self.logger.error("%s: %s (%s); more of the same are not logged", self.symbol, error, kind)
