"""The bots' front door: run it, read the state of things, pick what to do.

    bots

Nothing to remember and no flags. Every action says what it is about to do, and anything that
cannot be undone asks first.
"""

import asyncio
import signal
import sys
import time
import uuid
from collections import Counter
from collections.abc import Callable
from datetime import datetime
from decimal import Decimal, InvalidOperation
from pathlib import Path

import httpx

from bots.clients.reference_feed_client import ReferenceFeedClient
from bots.clients.spot_client import SpotApiError, SpotAsyncClient
from bots.entities.account import Account
from bots.entities.market import Market
from bots.entities.order import OrderStatus, OrderType, Side, TimeInForce
from bots.settings.settings import Settings
from bots.utils.money import D, fmt


class BotsCli:

    SIMULATOR = "trade_replay"
    # GET /v1/markets/{symbol}/ticker on a market nobody has traded yet. Not an error.
    NO_TICKER_YET = 6002
    # The signing filter does not know this key: the account is not in this database.
    UNKNOWN_KEY = 2101
    # A clock this far from the server's gets every signed request refused (2103).
    MAX_SKEW_MS = 1_000
    # Accounts are looked at this many at a time: each opens a connection of its own.
    AT_ONCE = 32
    # An order younger than this may not have reached the book yet; older, and it should be there.
    SETTLED_AFTER_SECONDS = 60
    # Orders that rest on the book. A conditional one waiting for its trigger rightly does not.
    ON_THE_BOOK = (OrderStatus.NEW, OrderStatus.PARTIALLY_FILLED)

    def __init__(self, config: Path | None = None, ask: Callable[[str], str] = input,
                 say: Callable[[str], None] = print):
        self.settings = Settings.load(config)
        self._ask_person = ask
        self._say_line = say
        self.public: SpotAsyncClient | None = None
        self.feed: ReferenceFeedClient | None = None
        self._markets_listed: dict[str, Market] | None = None
        self._accounts_listed: list[Account] = []
        self._working_accounts = 0
        self._open_orders = 0

    async def run(self) -> int:
        self.public = await SpotAsyncClient.create(api_url=self.settings.spot_api_url,
                                                   dev_url=self.settings.spot_dev_url)
        self.feed = await ReferenceFeedClient.create(rest_url=self.settings.feed_rest_url,
                                                       stream_url=self.settings.feed_stream_url)
        actions = {"1": self.check_everything, "2": self.show_markets, "3": self.create_accounts,
                   "4": self.cancel_every_order, "5": self.run_simulator, "6": self.change_settings}
        try:
            while True:
                await self._state_of_things()
                self._say("")
                self._say("  1  Check everything (places one test order far from the price, and cancels it)")
                self._say("  2  Show the markets")
                self._say("  3  Create bot accounts")
                self._say("  4  Cancel every open order of the bot accounts")
                self._say("  5  Run the trade simulator")
                self._say("  6  Settings")
                self._say("  q  Quit")
                choice = self._ask("> ").lower()
                if choice in ("q", "quit", "exit"):
                    return 0
                if choice in actions:
                    self._say("")
                    await actions[choice]()
                    self._ask("\nEnter to go back to the menu ")
        except (EOFError, KeyboardInterrupt):
            return 0
        finally:
            await asyncio.gather(self.public.close(), self.feed.close())

    # ------------------------------------------------------------------ talking to the person

    def _say(self, text: str, *values) -> None:
        self._say_line(text % values if values else text)

    def _problem(self, text: str, *values) -> None:
        self._say_line("  !! " + (text % values if values else text))

    def _ask(self, question: str) -> str:
        return self._ask_person(question).strip()

    def _yes(self, question: str, *, default: bool) -> bool:
        """The default is the capital letter, and what a bare Enter means: never yes for
        something that cannot be undone."""
        answer = self._ask(f"{question} [{'Y/n' if default else 'y/N'}] ").lower()
        return default if not answer else answer in ("y", "yes")

    def _symbols(self) -> list[str]:
        return list(self.settings.controller(self.SIMULATOR).get("symbols", [])) or ["BTCUSDT"]

    # ------------------------------------------------------------------ the state of things

    async def _state_of_things(self) -> int:
        """What is printed above the menu. Reads only."""
        self._say("")
        self._markets_listed, problems = await self._stack()
        if self._markets_listed is None:
            self._accounts_listed = []
            return problems
        self._accounts_listed, found = await self._accounts()
        return problems + found

    # ------------------------------------------------------------------ 1, 2: looking

    async def check_everything(self) -> None:
        problems = await self._state_of_things()
        if self._markets_listed is not None:
            await self._markets(self._markets_listed)
            if self._accounts_listed:
                problems += await self._round_trip(self._markets_listed, self._accounts_listed)
        self._say("")
        self._say("FAILED: %d problem%s, marked !! above", problems, "" if problems == 1 else "s") \
            if problems else self._say("OK: everything the bots need is there and working")

    async def show_markets(self) -> None:
        if self._markets_listed is None:
            self._problem("the trading API is down, so there is nothing to show")
        else:
            await self._markets(self._markets_listed)

    # ------------------------------------------------------------------ 3: accounts

    async def create_accounts(self) -> None:
        table, out = self.settings.accounts, self.settings.accounts_file
        count = self._number("How many accounts?", default=int(table.get("count", 150)), low=2, high=1000)
        fund = table.get("fund", "1000000")

        if out.exists():
            self._say("%s already holds %d accounts, and %d of them work.",
                      out.name, len(self._accounts_listed), self._working_accounts)
            self._say("Creating new ones adds %d more users to the database and replaces that file.", count)
            self._say("The old accounts stay in the database, but their API secrets are in that file and")
            self._say("nowhere else: once it is replaced, nothing can trade as them again.")
            if not self._yes(f"Replace them with {count} new accounts?", default=False):
                self._say("Nothing was changed.")
                return
        elif not self._yes(f"Create {count} accounts, each funded with {self._money(fund)} of every asset?",
                           default=True):
            return

        # Its own client: creating accounts takes as long as the count is large.
        slow = await SpotAsyncClient.create(api_url=self.settings.spot_api_url,
                                            dev_url=self.settings.spot_dev_url, request_timeout=300.0)
        try:
            self._say("Creating %d accounts...", count)
            created = await slow.create_dev_accounts(count, table.get("label", "bot"), fund)
        except (SpotApiError, httpx.HTTPError) as e:
            self._problem("could not create accounts: %s", self._short(e))
            self._say("It needs the local stack, dev profile included: docker compose --profile full up -d")
            return
        finally:
            await slow.close()
        self._say("Wrote %d accounts to %s", Account.save_all(out, created, overwrite=True), out)

    # ------------------------------------------------------------------ 4: orders

    async def cancel_every_order(self) -> None:
        if not self._open_orders:
            self._say("No bot account has an open order.")
            return
        self._say("The bot accounts have %d open orders.", self._open_orders)
        self._say("If the simulator is running it will put its orders straight back, so stop it first.")
        if not self._yes(f"Cancel all {self._open_orders}?", default=False):
            self._say("Nothing was changed.")
            return
        at_once = asyncio.Semaphore(self.AT_ONCE)
        cancelled = await asyncio.gather(*(self._cancel_all_of(a, at_once) for a in self._accounts_listed))
        self._say("Asked the exchange to cancel the orders of %d accounts.", sum(cancelled))

    async def _cancel_all_of(self, account: Account, at_once: asyncio.Semaphore) -> bool:
        async with at_once:
            client = await SpotAsyncClient.create(account.api_key, account.api_secret,
                                                  api_url=self.settings.spot_api_url)
            try:
                if not await client.get_open_orders():
                    return False
                await client.cancel_all_orders()
                return True
            except (SpotApiError, httpx.HTTPError) as e:
                self._problem("%s: %s", account.email, self._short(e))
                return False
            finally:
                await client.close()

    # ------------------------------------------------------------------ 5: the simulator

    async def run_simulator(self) -> None:
        listed = self._symbols()
        self._say("It replays the reference feed onto our markets, between two bot accounts a market, over")
        self._say("a book of %s orders a side that it keeps around the price.",
                  self.settings.controller(self.SIMULATOR).get("resting_orders_per_side", 150))
        self._say("Markets in the settings: %s", ", ".join(listed))
        answer = self._ask("Which markets? Enter for all of them, or name some (BTCUSDT,ETHUSDT): ").upper()
        symbols = [x.strip() for x in answer.split(",") if x.strip()] or listed
        unknown = [x for x in symbols if x not in listed]
        if unknown:
            self._problem("%s: not in the settings. Add a market there first (6), so it gets accounts.",
                          ", ".join(unknown))
            return
        self._say("Starting. Ctrl-C stops it, cancels what it has resting, and comes back here.\n")
        # Its own process, so it starts, crashes and stops on its own. Ctrl-C reaches both of
        # us: it is the simulator's to act on, and this waits for it to finish clearing up.
        ours = signal.signal(signal.SIGINT, signal.SIG_IGN)
        try:
            simulator = await asyncio.create_subprocess_exec(
                sys.executable, "-m", "bots.trade_replay_main",
                "--symbols", ",".join(symbols), "--config", str(self.settings.config_file))
            await simulator.wait()
        finally:
            signal.signal(signal.SIGINT, ours)

    # ------------------------------------------------------------------ 6: settings

    async def change_settings(self) -> None:
        while True:
            value = self.settings.controller(self.SIMULATOR).get
            self._say("The trade simulator (%s)", self.settings.config_file)
            self._say("  1  Markets                     %s", ", ".join(self._symbols()))
            self._say("  2  Orders kept on each side    %s", value("resting_orders_per_side", 150))
            self._say("  3  A resting order's size      about %s of the quote asset",
                      value("order_notional", "200"))
            self._say("  4  A replayed trade's size     %s to %s of the quote asset",
                      value("trade_notional_min", "10"), value("trade_notional_max", "100"))
            self._say("  5  How far from the price      %s to %s bps",
                      value("liquidity_min_bps", "5"), value("liquidity_max_bps", "300"))
            self._say("  6  When to trade               %s",
                      "on every print" if value("replay_every_trade", True)
                      else f"every {value('trade_interval_seconds', '1')} s at the price then")
            self._say("  A market's own sizes go under [%s.SYMBOL] in the file.", self.SIMULATOR)
            self._say("  b  Back")
            choice = self._ask("> ").lower()
            if choice in ("b", "", "q"):
                return
            if choice == "1":
                self._set_markets()
            elif choice == "2":
                self._set("resting_orders_per_side",
                          self._number("Orders on each side?", default=150, low=1, high=180))
            elif choice == "3":
                self._set("order_notional", self._amount("Typical size, in the quote asset?"))
            elif choice == "4":
                self._set_range("trade_notional_min", "trade_notional_max", "trade size, in the quote asset")
            elif choice == "5":
                self._set_range("liquidity_min_bps", "liquidity_max_bps", "distance from the price, in bps")
            elif choice == "6":
                self._set_when_to_trade()
            self._say("")

    def _set(self, key: str, value) -> None:
        if value is not None:
            self.settings = self.settings.write(self.SIMULATOR, key, value)
            self._say("Saved. The simulator reads it the next time it starts.")

    def _set_markets(self) -> None:
        known = sorted(self._markets_listed or {})
        if known:
            self._say("The exchange trades: %s", ", ".join(known))
        self._say("Each market takes two bot accounts; there are %d.", len(self._accounts_listed))
        answer = self._ask("Markets, separated by commas: ").upper()
        symbols = [x.strip() for x in answer.split(",") if x.strip()]
        unknown = [x for x in symbols if known and x not in known]
        if not symbols or unknown:
            self._problem("not changed: %s",
                          f"{', '.join(unknown)} is not traded here" if unknown else "none given")
        elif self._accounts_listed and len(symbols) * 2 > len(self._accounts_listed):
            self._problem("not changed: %d markets need %d accounts", len(symbols), len(symbols) * 2)
        else:
            self._set("symbols", symbols)

    def _set_when_to_trade(self) -> None:
        if self._yes("Trade on every print?", default=True):
            self._set("replay_every_trade", True)
            return
        seconds = self._amount("Seconds between trades?")
        if seconds is None:
            self._problem("not changed: seconds has to be a number above zero")
            return
        self._set("replay_every_trade", False)
        self._set("trade_interval_seconds", seconds)

    def _set_range(self, low_key: str, high_key: str, what: str) -> None:
        low, high = self._amount(f"Smallest {what}?"), self._amount(f"Largest {what}?")
        if low is None or high is None or D(low) > D(high):
            self._problem("not changed: the smallest has to be a number no larger than the largest")
            return
        self._set(low_key, low)
        self._set(high_key, high)

    def _number(self, question: str, *, default: int, low: int, high: int) -> int:
        answer = self._ask(f"{question} Enter for {default}: ")
        if answer.isdigit() and low <= int(answer) <= high:
            return int(answer)
        if answer:
            self._say("Not a number from %d to %d; using %d.", low, high, default)
        return default

    def _amount(self, question: str) -> str | None:
        """Money stays a decimal string, never a float (config values are read with Decimal)."""
        answer = self._ask(f"{question} ")
        try:
            return answer if D(answer) > 0 else None
        except (InvalidOperation, ValueError):
            return None

    # ------------------------------------------------------------------ what the checks look at

    async def _stack(self) -> tuple[dict[str, Market] | None, int]:
        problems, markets = 0, None
        try:
            server_time = (await self.public.get_server_time())["serverTime"]
            skew = SpotAsyncClient._get_timestamp() - self._to_ms(server_time)
            markets = Market.all_from_api(await self.public.get_markets_info())
            self._say("trading API %s: up, clock %+d ms", self.settings.spot_api_url, skew)
            if abs(skew) > self.MAX_SKEW_MS:
                self._problem("clock is %+d ms from the server's: signed requests will be refused (2103)",
                                  skew)
                problems += 1
        except (httpx.HTTPError, SpotApiError) as e:
            self._problem("trading API %s: DOWN (%s)", self.settings.spot_api_url, self._short(e))
            problems += 1

        # count=0 is refused by validation before anything is created: 400 means the endpoint is
        # there, 404 means this stack is not running the dev profile.
        try:
            await self.public.dev_request("POST", "/dev/accounts",
                                          body={"count": 0, "label": "probe", "fund": "1"})
            state = "up"
        except SpotApiError as e:
            state = "absent" if e.status == 404 else "up"
        except httpx.HTTPError as e:
            state = f"DOWN ({self._short(e)})"
        self._say("dev endpoints %s/dev: %s", self.settings.spot_dev_url, state)

        try:
            await self.feed.get_ticker_price("BTCUSDT")
            self._say("feed %s: up", self.settings.feed_rest_url)
        except httpx.HTTPError as e:
            self._problem("feed %s: DOWN (%s)", self.settings.feed_rest_url, self._short(e))
            problems += 1
        return markets, problems

    async def _markets(self, markets: dict[str, Market]) -> None:
        self._say("markets: %d listed, %d trading",
                         len(markets), sum(m.trading for m in markets.values()))

        tickers = {t["symbol"]: t for t in await self.public.get_tickers()}
        depths = dict(zip(markets, await asyncio.gather(
            *(self.public.get_depth(symbol, limit=1) for symbol in markets), return_exceptions=True),
            strict=True))
        try:
            real = {p["symbol"]: D(p["price"]) for p in await self.feed.get_ticker_price()}
        except httpx.HTTPError:
            real = {}

        for symbol in sorted(markets):
            ticker, depth, reference = tickers.get(symbol), depths[symbol], real.get(symbol)
            last = D(ticker["lastPrice"]) if ticker else None
            apart = f"{(last - reference) / reference * 100:+.2f}%" if last and reference else "--"
            bid = ask = None
            if isinstance(depth, dict):
                bid = depth["bids"][0]["price"] if depth["bids"] else None
                ask = depth["asks"][0]["price"] if depth["asks"] else None
            self._say("  %-9s %-8s last %-11s feed %-11s %-8s bid %-11s ask %-11s trades %s",
                        symbol, markets[symbol].status, self._money(last), self._money(reference), apart,
                        self._money(bid), self._money(ask),
                        f"{int(ticker['tradeCount']):,}" if ticker else "0")

    async def _accounts(self) -> tuple[list[Account], int]:
        try:
            accounts = Account.load_all(self.settings.accounts_file)
        except (FileNotFoundError, ValueError) as e:
            self._problem("accounts: none (%s)", e)
            return [], 1

        at_once = asyncio.Semaphore(self.AT_ONCE)
        looked = await asyncio.gather(*(self._look_at(a, at_once) for a in accounts))
        known = [x for x in looked if x["known"]]
        can_trade = sum(x["can_trade"] for x in known)
        orders = [o for x in known for o in x["orders"]]
        self._working_accounts, self._open_orders = can_trade, len(orders)

        self._say("accounts: %d in file, %d in database, %d can trade",
                         len(accounts), len(known), can_trade)
        by_status = Counter(o["status"] for o in orders)
        self._say("open orders: %d%s", len(orders),
                    f" ({', '.join(f'{status} {count}' for status, count in sorted(by_status.items()))})"
                    if orders else "")

        problems = 0
        if len(known) < len(accounts):
            self._problem("%d accounts are not in this database", len(accounts) - len(known))
            problems += 1
        if can_trade < len(known):
            self._problem("%d accounts may not trade", len(known) - can_trade)
            problems += 1
        lost = await self._ghosts(orders)
        if lost:
            self._problem("%d open orders are not on the book: the engine does not have them", len(lost))
            problems += 1
        return accounts, problems

    async def _look_at(self, account: Account, at_once: asyncio.Semaphore) -> dict:
        async with at_once:
            client = await SpotAsyncClient.create(account.api_key, account.api_secret,
                                                  api_url=self.settings.spot_api_url)
            try:
                who, orders = await asyncio.gather(client.get_account(), client.get_open_orders())
            except (SpotApiError, httpx.HTTPError) as e:
                # Any failure but an unknown key still means the exchange knows the account.
                return {"known": not (isinstance(e, SpotApiError) and e.code == self.UNKNOWN_KEY),
                        "can_trade": False, "orders": []}
            finally:
                await client.close()
        return {"known": True, "can_trade": who["canTrade"], "orders": orders}

    async def _ghosts(self, orders: list[dict]) -> list[dict]:
        """Orders the database calls open that are not on the public book.

        Every resting order is a price level on its side of the book. One that has been open for a
        minute and has no level is an order the matching engine does not have: it can never fill
        and never cancel, and the funds behind it stay locked.
        """
        now = time.time()
        resting = [o for o in orders if o["status"] in self.ON_THE_BOOK and o.get("price")
                   and now - self._to_ms(o["createdAt"]) / 1000 > self.SETTLED_AFTER_SECONDS]
        if not resting:
            return []
        symbols = sorted({o["symbol"] for o in resting})
        books = dict(zip(symbols, await asyncio.gather(
            *(self.public.get_depth(symbol, limit=5000) for symbol in symbols)), strict=True))
        levels = {(symbol, side): {D(level["price"]) for level in book[key]}
                  for symbol, book in books.items() for side, key in (("BUY", "bids"), ("SELL", "asks"))}
        return [o for o in resting if D(o["price"]) not in levels[(o["symbol"], o["side"])]]

    async def _round_trip(self, markets: dict[str, Market], accounts: list[Account]) -> int:
        symbol, index = self._symbols()[0], 0
        market = markets.get(symbol)
        if index >= len(accounts) or market is None or not market.trading:
            self._problem("no test order: %s is not a market that is trading", symbol)
            return 1
        account = accounts[index]
        spot = await SpotAsyncClient.create(account.api_key, account.api_secret,
                                            api_url=self.settings.spot_api_url)
        order_id, timings = None, {}
        try:
            # Half the going price: far enough that nothing will ever take it, and LIMIT_MAKER so
            # that even if something could, it is refused rather than filled.
            reference = await self._reference(spot, symbol)
            price = market.price(reference / 2, side=Side.BUY)
            quantity = market.quantity(market.min_notional * 2 / price, price=price)
            illegal = market.check(price, quantity)
            if illegal:
                raise ValueError(f"could not size a legal order: {illegal}")

            started = time.perf_counter()
            client_order_id = f"check-{uuid.uuid4().hex[:16]}"
            placed = await spot.create_order(symbol, Side.BUY, OrderType.LIMIT_MAKER,
                                             quantity=quantity, price=price, time_in_force=TimeInForce.GTC,
                                             client_order_id=client_order_id)
            order_id = placed["orderId"]
            timings["place"] = self._ms(started)
            self._say("placed %s as %s: BUY %s @ %s (reference %s)",
                        order_id, account.email, fmt(quantity), fmt(price), fmt(reference))

            started = time.perf_counter()
            read = await spot.get_order(order_id)
            timings["read"] = self._ms(started)
            if read["clientOrderId"] != client_order_id:
                raise ValueError("read back a different order than was placed")
            self._say("read back: %s", read["status"])

            started = time.perf_counter()
            await spot.cancel_order(order_id)
            status = await self._await_closed(spot, order_id)
            timings["cancel"] = self._ms(started)
            order_id = None
            self._say("cancelled: %s", status)
            if status != OrderStatus.CANCELED:
                raise ValueError(f"the order ended {status}, not CANCELED")

            self._say("latency ms: %s", "  ".join(f"{step} {ms:.0f}" for step, ms in timings.items()))
            return 0
        except (SpotApiError, ValueError, httpx.HTTPError) as e:
            self._problem("test order failed: %s", self._short(e))
            return 1
        finally:
            if order_id is not None:
                # Never leave the test order resting because a later step failed.
                try:
                    await spot.cancel_order(order_id)
                except (SpotApiError, httpx.HTTPError):
                    pass
            await spot.close()

    async def _reference(self, spot: SpotAsyncClient, symbol: str) -> Decimal:
        """Our last price, or the real market's where ours has never traded."""
        try:
            return D((await spot.get_ticker(symbol))["lastPrice"])
        except SpotApiError as e:
            if e.code != self.NO_TICKER_YET:
                raise
        return D((await self.feed.get_ticker_price(symbol))["price"])

    async def _await_closed(self, spot: SpotAsyncClient, order_id: str, seconds: float = 5.0) -> str:
        """A cancel answers 202 before the engine applies it, so the status is read until it lands."""
        deadline = time.monotonic() + seconds
        while True:
            status = (await spot.get_order(order_id))["status"]
            if not OrderStatus(status).is_open or time.monotonic() >= deadline:
                return status
            await asyncio.sleep(0.1)

    @staticmethod
    def _to_ms(instant: str) -> int:
        """`2026-09-21T07:20:15.138294302Z` -> epoch milliseconds. The server sends nanoseconds,
        which `fromisoformat` refuses, so the fraction is cut to the six digits it takes."""
        head, _, fraction = instant.rstrip("Z").partition(".")
        return int(datetime.fromisoformat(f"{head}.{(fraction + '000000')[:6]}+00:00").timestamp() * 1000)

    @staticmethod
    def _money(value) -> str:
        """For reading, not for the wire: thousands separated, no trailing zeros, `--` for nothing."""
        if value is None:
            return "--"
        text = f"{D(value):,f}"
        return text.rstrip("0").rstrip(".") if "." in text else text

    @staticmethod
    def _ms(started: float) -> float:
        return (time.perf_counter() - started) * 1000

    @staticmethod
    def _short(error: Exception) -> str:
        if isinstance(error, SpotApiError):
            return f"code {error.code}: {error.message}" if error.code else f"HTTP {error.status}"
        return str(error) or error.__class__.__name__


def main() -> int:
    return asyncio.run(BotsCli().run())


if __name__ == "__main__":
    sys.exit(main())
