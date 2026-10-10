import time
from datetime import UTC, datetime

from bots.cli_main import BotsCli


def test_the_servers_nanosecond_instant_becomes_epoch_milliseconds():
    """/v1/time sends nine fractional digits, which datetime.fromisoformat refuses outright."""
    assert BotsCli._to_ms("2026-09-21T07:20:15.138294302Z") == 1_789_975_215_138


def test_an_instant_with_no_fraction_or_a_short_one_still_parses():
    assert BotsCli._to_ms("2026-09-21T07:20:15Z") == 1_789_975_215_000
    assert BotsCli._to_ms("2026-09-21T07:20:15.5Z") == 1_789_975_215_500


def test_money_is_written_for_reading():
    assert BotsCli._money("77896.94000000") == "77,896.94"
    assert BotsCli._money("1000000") == "1,000,000"
    assert BotsCli._money("0.00051000") == "0.00051"
    assert BotsCli._money(None) == "--"


class FakePublic:
    def __init__(self, books):
        self._books = books
        self.asked: list[str] = []

    async def get_depth(self, symbol, limit=None, grouping=None):
        self.asked.append(symbol)
        return self._books[symbol]


def order(symbol, side, price, *, status="NEW", age_seconds=3600):
    created = datetime.fromtimestamp(time.time() - age_seconds, UTC).strftime("%Y-%m-%dT%H:%M:%S.%fZ")
    return {"symbol": symbol, "side": side, "price": price, "status": status, "createdAt": created}


BOOK = {"BTCUSDT": {"bids": [{"price": "68121.29", "quantity": "0.02"}], "asks": []}}



def checking(public):
    """The program, looking at this public API."""
    cli = BotsCli()
    cli.public = public
    return cli


class TestGhosts:
    async def test_an_open_order_with_no_level_on_the_book_is_one_the_engine_has_lost(self):
        on_the_book = order("BTCUSDT", "BUY", "68121.29")
        lost = order("BTCUSDT", "BUY", "68000.00")

        assert await checking(FakePublic(BOOK))._ghosts([on_the_book, lost]) == [lost]

    async def test_the_side_matters(self):
        """A sell at a price that is only a bid level is not on the book."""
        sell = order("BTCUSDT", "SELL", "68121.29")
        assert await checking(FakePublic(BOOK))._ghosts([sell]) == [sell]

    async def test_a_price_is_compared_as_a_number_not_as_text(self):
        assert await checking(FakePublic(BOOK))._ghosts([order("BTCUSDT", "BUY", "68121.2900")]) == []

    async def test_an_order_placed_a_moment_ago_is_given_time_to_reach_the_book(self):
        young = order("BTCUSDT", "BUY", "1.00", age_seconds=5)
        assert await checking(FakePublic(BOOK))._ghosts([young]) == []

    async def test_a_conditional_order_waiting_for_its_trigger_is_rightly_off_the_book(self):
        waiting = order("BTCUSDT", "SELL", "60000.00", status="PENDING_TRIGGER")
        assert await checking(FakePublic(BOOK))._ghosts([waiting]) == []

    async def test_with_nothing_resting_no_book_is_even_fetched(self):
        public = FakePublic(BOOK)
        assert await checking(public)._ghosts([]) == []
        assert public.asked == []
