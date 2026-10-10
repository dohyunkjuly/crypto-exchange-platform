"""Settings: `config/bots.toml` for what a deployment decides, the environment to override it.

An environment variable wins over the file, so one machine changes a URL without editing
anything: `BOTS_SPOT_API_URL` overrides `[spot] api_url`.

Money in the file is quoted. A bare TOML number would be a float before this module ever read
it, and money is never a float here (AGENTS.md).
"""

import json
import os
import re
import tomllib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, ClassVar

from ..entities.account import Account

__all__ = ["Env", "Settings", "BOTS_DIR", "DEFAULT_CONFIG"]

# bots/, wherever the process was started from.
BOTS_DIR = Path(__file__).resolve().parents[3]
DEFAULT_CONFIG = BOTS_DIR / "config" / "bots.toml"


class Env:
    @staticmethod
    def get_str(key: str, default: str = "") -> str:
        return os.getenv(key, default)

    @staticmethod
    def get_int(key: str, default: int = 0) -> int:
        try:
            return int(os.getenv(key, default))
        except (ValueError, TypeError):
            return default

    @staticmethod
    def get_bool(key: str, default: bool = False) -> bool:
        value = os.getenv(key)
        if value is None:
            return default
        return value.strip().lower() in ("true", "1", "yes", "y", "t")

    @staticmethod
    def get_list(key: str, separator: str = ",", default: list[str] | None = None) -> list[str]:
        value = os.getenv(key)
        if value is None:
            return default or []
        return [item.strip() for item in value.split(separator) if item.strip()]


@dataclass(frozen=True, slots=True)
class Settings:
    spot_api_url: str
    spot_dev_url: str
    feed_rest_url: str
    feed_stream_url: str
    accounts_file: Path
    # One table per controller, as its *_main reads it: [trade_replay], ...
    controllers: dict[str, dict[str, Any]] = field(default_factory=dict)
    # The `[accounts]` table: where the file is, and what `bots` creates when asked for accounts.
    accounts: dict[str, Any] = field(default_factory=dict)
    # The file this was read from, so a changed value goes back to the same one.
    config_file: Path = DEFAULT_CONFIG

    # How many accounts one market of a controller trades with, where that is not one. What the
    # controller is, not something to configure: the trade simulator has one account keeping
    # the bids and one keeping the asks, and no use for a third.
    ACCOUNTS_PER_SYMBOL: ClassVar[dict[str, int]] = {"trade_replay": 2}

    @classmethod
    def load(cls, path: Path | None = None) -> "Settings":
        path = path or Path(Env.get_str("BOTS_CONFIG") or DEFAULT_CONFIG)
        raw = tomllib.loads(path.read_text()) if path.exists() else {}
        spot, feed, accounts = raw.get("spot", {}), raw.get("reference_feed", {}), raw.get("accounts", {})

        accounts_file = Path(Env.get_str("BOTS_ACCOUNTS_FILE")
                             or accounts.get("file", "config/accounts.json"))
        if not accounts_file.is_absolute():
            accounts_file = BOTS_DIR / accounts_file

        known = {"spot", "reference_feed", "accounts"}
        return cls(
            spot_api_url=Env.get_str("BOTS_SPOT_API_URL") or spot.get("api_url", "http://localhost:8080"),
            spot_dev_url=Env.get_str("BOTS_SPOT_DEV_URL") or spot.get("dev_url", "http://localhost:8081"),
            feed_rest_url=Env.get_str("BOTS_FEED_REST_URL")
            or feed.get("rest_url", "https://api.binance.com"),
            feed_stream_url=Env.get_str("BOTS_FEED_STREAM_URL")
            or feed.get("stream_url", "wss://stream.binance.com:9443"),
            accounts_file=accounts_file,
            accounts=accounts,
            config_file=path,
            controllers={k: v for k, v in raw.items() if k not in known and isinstance(v, dict)},
        )

    def write(self, table: str, key: str, value: str | int | bool | list[str]) -> "Settings":
        """Changes one value in the config file and reads the file again.

        Only that line is touched: the file is written by hand, and its comments are why each
        value is what it is. `tomllib` reads and does not write, so the line is found by its
        table and key -- which is enough for this file, where every value sits on one line.
        """
        written = json.dumps(value)     # a TOML string, integer, boolean and list of strings are JSON's
        lines = self.config_file.read_text().splitlines()
        inside, header = False, None
        for i, line in enumerate(lines):
            if line.startswith("["):
                inside = line.strip() == f"[{table}]"
                header = i if inside else header
            elif inside and re.match(rf"{re.escape(key)}\s*=", line):
                comment = re.search(r"\s+#.*$", line)
                lines[i] = f"{key} = {written}{comment.group(0) if comment else ''}"
                break
        else:
            if header is None:
                raise KeyError(f"[{table}] is not in {self.config_file}")
            lines.insert(header + 1, f"{key} = {written}")
        self.config_file.write_text("\n".join(lines) + "\n")
        return Settings.load(self.config_file)

    def controller(self, name: str) -> dict[str, Any]:
        """The `[name]` table, or empty: a controller with no settings runs on its defaults."""
        return self.controllers.get(name, {})

    def accounts_for(self, controller: str, symbols: list[str]) -> dict[str, list[Account]]:
        """The accounts each of `symbols` trades with, none of them given to another controller.

        Two controllers on one account AND one market would keep wiping each other's orders:
        each cancels what its account has open in its market, on the way in and on the way out.
        So accounts are handed out in blocks, in the order the controllers appear in the
        config, and the same controller gets the same accounts on every run. A block is either:

        * so many accounts per symbol the table lists: one, unless ACCOUNTS_PER_SYMBOL says
          otherwise. A symbol gets the accounts at its position in that list, so running one
          market alone still uses that market's own.
        * `accounts = N` -- one pool of N that every symbol of the controller shares. Safe,
          because the cancelling is per market, and needed by anything that trades with itself:
          a trade has a buyer and a seller, and the exchange refuses one user on both sides.
        """
        first = 0
        for name, table in self.controllers.items():
            if name == controller:
                break
            first += self._block(name, table)
        table = self.controller(controller)
        listed, pool = table.get("symbols", []), table.get("accounts")

        everyone = Account.load_all(self.accounts_file)
        each = self.ACCOUNTS_PER_SYMBOL.get(controller, 1)
        needed = self._block(controller, table) or len(symbols) * each
        if first + needed > len(everyone):
            raise ValueError(f"[{controller}] needs accounts {first}..{first + needed - 1}, and "
                             f"{self.accounts_file} holds {len(everyone)}")
        block = everyone[first:first + needed]

        if pool:
            return dict.fromkeys(symbols, block)
        if not listed:
            return {symbol: block[i * each:(i + 1) * each] for i, symbol in enumerate(symbols)}
        unknown = [symbol for symbol in symbols if symbol not in listed]
        if unknown:
            # The accounts after this block belong to the next controller, so a market gets an
            # account by being listed, never by being asked for.
            raise ValueError(f"[{controller}] does not list {', '.join(unknown)}: add to its symbols "
                             "in the config")
        return {symbol: block[listed.index(symbol) * each:(listed.index(symbol) + 1) * each]
                for symbol in symbols}

    @classmethod
    def _block(cls, controller: str, table: dict[str, Any]) -> int:
        """How many accounts a controller's table takes: its pool, or so many per symbol it lists."""
        return int(table.get("accounts")
                   or len(table.get("symbols", [])) * cls.ACCOUNTS_PER_SYMBOL.get(controller, 1))
