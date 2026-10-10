import json

import httpx
import pytest

from bots import cli_main
from bots.cli_main import BotsCli
from bots.entities.account import Account

CONFIG = ('[spot]\napi_url = "http://api"\ndev_url = "http://dev"\n'
          '[accounts]\nfile = "{file}"\ncount = 4\n'
          '[trade_replay]\n# how deep the book is\nresting_orders_per_side = 150\n'
          'trade_notional_min = "10"\ntrade_notional_max = "100"\nsymbols = ["BTCUSDT"]\n')
CREATED = {"password": "p", "accounts": [
    {"email": f"bot-{i}@x.dev", "userId": i, "apiKey": f"ak_{i}", "apiSecret": "s", "totpSecret": "T"}
    for i in range(4)]}


class Person:
    """Types the answers in order, and remembers everything the program said."""

    def __init__(self, *answers):
        self.answers, self.heard, self.asked = list(answers), [], []

    def ask(self, question):
        self.asked.append(question)
        if not self.answers:
            raise EOFError
        return self.answers.pop(0)

    def say(self, line):
        self.heard.append(line)

    @property
    def everything(self):
        return "\n".join(self.heard + self.asked)


class FakeSpot:
    created: list = []

    @classmethod
    async def create(cls, *_, **__):
        return cls()

    async def create_dev_accounts(self, count, label, fund):
        FakeSpot.created.append((count, label, fund))
        return CREATED

    async def close(self):
        pass


@pytest.fixture
def program(tmp_path, monkeypatch):
    def make(*answers, accounts_file_exists=False):
        accounts_file = tmp_path / "accounts.json"
        if accounts_file_exists:
            Account.save_all(accounts_file, {"password": "old", "accounts": CREATED["accounts"][:2]})
        config = tmp_path / "bots.toml"
        config.write_text(CONFIG.format(file=accounts_file))
        person = Person(*answers)
        cli = BotsCli(config, ask=person.ask, say=person.say)
        cli._accounts_listed = Account.load_all(accounts_file) if accounts_file_exists else []
        cli._working_accounts = len(cli._accounts_listed)
        return cli, person, accounts_file

    FakeSpot.created = []
    monkeypatch.setattr(cli_main, "SpotAsyncClient", FakeSpot)
    return make


class TestCreateAccounts:
    async def test_on_a_fresh_stack_enter_twice_creates_what_the_settings_say(self, program):
        cli, person, accounts_file = program("", "")
        await cli.create_accounts()

        assert FakeSpot.created == [(4, "bot", "1000000")]
        assert len(Account.load_all(accounts_file)) == 4

    async def test_with_accounts_already_there_it_says_what_is_lost_and_enter_changes_nothing(self, program):
        """Nobody has to know a flag: the program sees the file and says what replacing it costs."""
        cli, person, accounts_file = program("", "", accounts_file_exists=True)
        before = accounts_file.read_text()
        await cli.create_accounts()

        assert "already holds 2 accounts, and 2 of them work" in person.everything
        assert "nowhere else" in person.everything and "[y/N]" in person.everything
        assert FakeSpot.created == [] and accounts_file.read_text() == before
        assert person.heard[-1] == "Nothing was changed."

    async def test_a_typed_yes_replaces_them(self, program):
        cli, person, accounts_file = program("", "y", accounts_file_exists=True)
        await cli.create_accounts()

        assert json.loads(accounts_file.read_text())["password"] == "p"

    async def test_a_stack_without_the_dev_profile_is_explained_not_a_traceback(self, program, monkeypatch):
        async def refuse(self, *_):
            raise httpx.ConnectError("refused")
        monkeypatch.setattr(FakeSpot, "create_dev_accounts", refuse)
        cli, person, accounts_file = program("", "")
        await cli.create_accounts()

        assert "could not create accounts" in person.everything and "docker compose" in person.everything
        assert not accounts_file.exists()


class TestCancelEveryOrder:
    async def test_with_nothing_open_it_says_so_and_asks_nothing(self, program):
        cli, person, _ = program()
        await cli.cancel_every_order()

        assert person.heard == ["No bot account has an open order."] and person.asked == []

    async def test_it_says_how_many_and_enter_changes_nothing(self, program):
        cli, person, _ = program("")
        cli._open_orders = 301
        await cli.cancel_every_order()

        assert "301 open orders" in person.everything and person.heard[-1] == "Nothing was changed."


class TestSettings:
    async def test_a_changed_value_is_saved_to_the_file_with_its_comments_kept(self, program):
        cli, person, _ = program("2", "20", "b")
        await cli.change_settings()

        text = cli.settings.config_file.read_text()
        assert "resting_orders_per_side = 20" in text and "# how deep the book is" in text
        assert cli.settings.controller("trade_replay")["resting_orders_per_side"] == 20

    async def test_a_range_upside_down_is_refused_and_nothing_is_written(self, program):
        cli, person, _ = program("4", "500", "5", "b")
        before = cli.settings.config_file.read_text()
        await cli.change_settings()

        assert "not changed" in person.everything and cli.settings.config_file.read_text() == before

    async def test_more_markets_than_there_are_pairs_of_accounts_is_refused(self, program):
        cli, person, _ = program("1", "BTCUSDT,ETHUSDT", "b", accounts_file_exists=True)
        await cli.change_settings()

        assert "2 markets need 4 accounts" in person.everything
        assert cli.settings.controller("trade_replay")["symbols"] == ["BTCUSDT"]


class TestSimulator:
    async def test_a_market_the_settings_do_not_list_is_refused_with_where_to_add_it(self, program):
        cli, person, _ = program("DOGEUSDT")
        await cli.run_simulator()

        assert "DOGEUSDT: not in the settings" in person.everything


async def test_the_menu_opens_on_the_state_of_things_even_with_the_stack_down(program, monkeypatch):
    class Down(FakeSpot):
        async def get_server_time(self):
            raise httpx.ConnectError("refused")

        async def dev_request(self, *_, **__):
            raise httpx.ConnectError("refused")

        async def get_ticker_price(self, *_):
            raise httpx.ConnectError("refused")

    monkeypatch.setattr(cli_main, "SpotAsyncClient", Down)
    monkeypatch.setattr(cli_main, "ReferenceFeedClient", Down)
    cli, person, _ = program("q")

    assert await cli.run() == 0
    assert "trading API http://api: DOWN" in person.everything
    assert "  3  Create bot accounts" in person.heard
