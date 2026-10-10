"""The bot accounts, and the file that holds them.

`bots` creates them, from its menu, and writes the file. It is the only copy of each API secret --
the exchange returns a secret once and stores it encrypted -- so it is a credential: never
committed, and never overwritten without being asked to.
"""

import json
from dataclasses import dataclass
from pathlib import Path

__all__ = ["Account"]


@dataclass(frozen=True, slots=True)
class Account:
    email: str
    user_id: int
    api_key: str
    api_secret: str
    totp_secret: str

    @classmethod
    def load_all(cls, path: Path) -> list["Account"]:
        """Every account, in file order, so the one at index 3 is the same account on every run."""
        if not path.exists():
            raise FileNotFoundError(
                f"{path} is missing. Create the accounts first: run `bots` and pick \"Create bot accounts\", "
                "against a stack running the dev profile."
            )
        payload = json.loads(path.read_text())
        accounts = [
            cls(a["email"], int(a["userId"]), a["apiKey"], a["apiSecret"], a["totpSecret"])
            for a in payload["accounts"]
        ]
        if not accounts:
            raise ValueError(f"{path} lists no accounts")
        return accounts

    @staticmethod
    def save_all(path: Path, payload: dict, *, overwrite: bool = False) -> int:
        """Writes `POST /dev/accounts`' answer as it came. Returns how many accounts it holds."""
        if path.exists() and not overwrite:
            raise FileExistsError(
                f"{path} already holds accounts, and it is the only copy of their secrets. "
                "Pass --force to replace it."
            )
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload, indent=2) + "\n")
        # A credential: the owner may read it, nobody else.
        path.chmod(0o600)
        return len(payload["accounts"])
