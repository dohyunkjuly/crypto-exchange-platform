import json
import stat

import pytest

from bots.entities.account import Account
from bots.settings.settings import Settings

CREATED = {"password": "p", "accounts": [
    {"email": "bot-001@x.dev", "userId": 4, "apiKey": "ak_1", "apiSecret": "s1", "totpSecret": "T1"},
    {"email": "bot-002@x.dev", "userId": 5, "apiKey": "ak_2", "apiSecret": "s2", "totpSecret": "T2"}]}


class TestAccounts:
    def test_what_the_endpoint_answers_is_what_gets_loaded(self, tmp_path):
        path = tmp_path / "config" / "accounts.json"
        assert Account.save_all(path, CREATED) == 2

        accounts = Account.load_all(path)
        assert [a.email for a in accounts] == ["bot-001@x.dev", "bot-002@x.dev"]
        assert accounts[0].api_secret == "s1" and accounts[0].user_id == 4

    def test_an_existing_file_is_never_replaced_unasked(self, tmp_path):
        """It is the only copy of every secret in it."""
        path = tmp_path / "accounts.json"
        Account.save_all(path, CREATED)
        with pytest.raises(FileExistsError):
            Account.save_all(path, {"password": "p", "accounts": []})

        assert len(json.loads(path.read_text())["accounts"]) == 2
        one = {"password": "p", "accounts": CREATED["accounts"][:1]}
        assert Account.save_all(path, one, overwrite=True) == 1

    def test_the_file_is_readable_by_its_owner_only(self, tmp_path):
        path = tmp_path / "accounts.json"
        Account.save_all(path, CREATED)
        assert stat.S_IMODE(path.stat().st_mode) == 0o600

    def test_a_missing_file_says_how_to_make_one(self, tmp_path):
        with pytest.raises(FileNotFoundError, match="Create bot accounts"):
            Account.load_all(tmp_path / "nope.json")


class TestSettings:
    def test_the_file_is_read_and_each_controller_gets_its_table(self, tmp_path):
        config = tmp_path / "bots.toml"
        config.write_text('[spot]\napi_url = "http://api:1"\n'
                          '[lp]\nsymbols = ["BTCUSDT"]\n')
        settings = Settings.load(config)

        assert settings.spot_api_url == "http://api:1"
        assert settings.controller("lp") == {"symbols": ["BTCUSDT"]}
        assert settings.controller("pricer") == {}

    def test_the_environment_wins_over_the_file(self, tmp_path, monkeypatch):
        config = tmp_path / "bots.toml"
        config.write_text('[spot]\napi_url = "http://from-file:1"\n')
        monkeypatch.setenv("BOTS_SPOT_API_URL", "http://from-env:2")

        assert Settings.load(config).spot_api_url == "http://from-env:2"

    def test_no_file_at_all_still_gives_a_local_stack(self, tmp_path):
        settings = Settings.load(tmp_path / "missing.toml")
        assert settings.spot_api_url == "http://localhost:8080"
        assert settings.spot_dev_url == "http://localhost:8081"


class TestAccountsFor:
    """Two controllers on one account and one market would cancel each other's orders."""

    CONFIG = ('[pricer]\nsymbols = ["BTCUSDT", "ETHUSDT"]\n'
              '[lp]\nsymbols = ["BTCUSDT", "ETHUSDT", "SOLUSDT"]\n'
              '[pooled]\naccounts = 4\nsymbols = ["BTCUSDT", "ETHUSDT"]\n'
              '[trade_replay]\nsymbols = ["BTCUSDT", "ETHUSDT"]\n'
              '[after]\nsymbols = ["BTCUSDT"]\n')

    def settings(self, tmp_path, count=12):
        accounts = [{"email": f"bot-{i}@x.dev", "userId": i, "apiKey": f"ak_{i}", "apiSecret": "s",
                     "totpSecret": "T"} for i in range(count)]
        Account.save_all(tmp_path / "accounts.json", {"password": "p", "accounts": accounts})
        config = tmp_path / "bots.toml"
        config.write_text(f'[accounts]\nfile = "{tmp_path / "accounts.json"}"\n' + self.CONFIG)
        return Settings.load(config)

    @staticmethod
    def ids(pools):
        return {symbol: [a.user_id for a in pool] for symbol, pool in pools.items()}

    def test_each_controller_gets_the_block_after_the_one_before_it(self, tmp_path):
        settings = self.settings(tmp_path)

        assert self.ids(settings.accounts_for("pricer", ["BTCUSDT", "ETHUSDT"])) == {
            "BTCUSDT": [0], "ETHUSDT": [1]}
        assert self.ids(settings.accounts_for("lp", ["BTCUSDT", "ETHUSDT", "SOLUSDT"])) == {
            "BTCUSDT": [2], "ETHUSDT": [3], "SOLUSDT": [4]}

    def test_a_market_run_alone_still_uses_its_own_account_not_the_first_one(self, tmp_path):
        assert self.ids(self.settings(tmp_path).accounts_for("lp", ["SOLUSDT"])) == {"SOLUSDT": [4]}

    def test_a_pool_is_shared_by_every_market_of_that_controller(self, tmp_path):
        """A trade needs a buyer and a seller who are different users, so one account a market
        could never trade with itself. Sharing is safe: the cancelling is per market."""
        pools = self.ids(self.settings(tmp_path).accounts_for("pooled",
                                                              ["BTCUSDT", "ETHUSDT"]))
        assert pools == {"BTCUSDT": [5, 6, 7, 8], "ETHUSDT": [5, 6, 7, 8]}

    def test_the_simulator_gets_two_accounts_a_market_and_a_market_alone_keeps_its_own(self, tmp_path):
        settings = self.settings(tmp_path, count=13)

        assert self.ids(settings.accounts_for("trade_replay", ["BTCUSDT", "ETHUSDT"])) == {
            "BTCUSDT": [9, 10], "ETHUSDT": [11, 12]}
        alone = settings.accounts_for("trade_replay", ["ETHUSDT"])
        assert self.ids(alone) == {"ETHUSDT": [11, 12]}

    def test_the_controller_after_it_starts_past_both_of_each_markets_accounts(self, tmp_path):
        after = self.settings(tmp_path, count=14).accounts_for("after", ["BTCUSDT"])
        assert self.ids(after) == {"BTCUSDT": [13]}

    def test_two_a_market_are_counted_when_the_file_is_too_short(self, tmp_path):
        with pytest.raises(ValueError, match="holds 12"):
            self.settings(tmp_path, count=12).accounts_for("trade_replay", ["BTCUSDT", "ETHUSDT"])

    def test_a_pool_does_not_overlap_the_controllers_before_it(self, tmp_path):
        settings = self.settings(tmp_path)
        taken = [a.user_id for name, symbols in [("pricer", ["BTCUSDT", "ETHUSDT"]),
                                                  ("lp", ["BTCUSDT", "ETHUSDT", "SOLUSDT"])]
                 for pool in settings.accounts_for(name, symbols).values() for a in pool]
        pool = settings.accounts_for("pooled", ["BTCUSDT"])["BTCUSDT"]

        assert not set(taken) & {a.user_id for a in pool}

    def test_a_market_the_config_does_not_list_is_refused_rather_than_given_the_next_ones(self, tmp_path):
        with pytest.raises(ValueError, match="does not list XRPUSDT"):
            self.settings(tmp_path).accounts_for("pricer", ["BTCUSDT", "XRPUSDT"])

    def test_too_few_accounts_in_the_file_says_so(self, tmp_path):
        with pytest.raises(ValueError, match="holds 6"):
            self.settings(tmp_path, count=6).accounts_for("pooled", ["BTCUSDT"])


class TestWrite:
    CONFIG = ('# why this file exists\n[spot]\napi_url = "http://x"\n\n[sim]\n# how deep the book is\n'
              'depth = 150   # per side\nsymbols = ["BTCUSDT", "ETHUSDT"]\n\n[other]\ndepth = 1\n')

    def written(self, tmp_path, key, value):
        config = tmp_path / "bots.toml"
        config.write_text(self.CONFIG)
        return Settings.load(config).write("sim", key, value), config.read_text()

    def test_one_value_changes_and_every_comment_and_every_other_line_stays(self, tmp_path):
        settings, text = self.written(tmp_path, "depth", 20)

        assert settings.controller("sim")["depth"] == 20
        assert text == self.CONFIG.replace("depth = 150   # per side", "depth = 20   # per side")

    def test_the_same_key_in_another_table_is_not_the_one_changed(self, tmp_path):
        settings, _ = self.written(tmp_path, "depth", 20)
        assert settings.controller("other")["depth"] == 1

    def test_a_list_and_a_string_are_written_as_toml_reads_them(self, tmp_path):
        settings, _ = self.written(tmp_path, "symbols", ["SOLUSDT"])
        assert settings.controller("sim")["symbols"] == ["SOLUSDT"]
        settings, _ = self.written(tmp_path, "size", "55.5")
        assert settings.controller("sim")["size"] == "55.5"

    def test_a_flag_is_written_as_toml_reads_it(self, tmp_path):
        settings, text = self.written(tmp_path, "every", False)
        assert settings.controller("sim")["every"] is False
        assert "every = false" in text

    def test_a_key_the_table_does_not_have_yet_is_added_to_that_table(self, tmp_path):
        settings, _ = self.written(tmp_path, "size", "10")
        assert settings.controller("sim") == {"size": "10", "depth": 150, "symbols": ["BTCUSDT", "ETHUSDT"]}
