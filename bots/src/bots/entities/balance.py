"""One asset of one account."""

from dataclasses import dataclass
from decimal import Decimal

from ..utils.money import D

__all__ = ["Balance"]


@dataclass(frozen=True, slots=True)
class Balance:
    asset: str
    available: Decimal
    locked: Decimal
    # The exchange's counter for this row. Frames can arrive out of order; the higher one wins.
    version: int

    @property
    def total(self) -> Decimal:
        return self.available + self.locked

    @classmethod
    def from_api(cls, raw: dict) -> "Balance":
        """`GET /v1/wallet/balances` says `available`; the `balanceChanged` frame says `free`."""
        available = raw["available"] if "available" in raw else raw["free"]
        return cls(asset=raw["asset"], available=D(available), locked=D(raw["locked"]),
                   version=int(raw["version"]))
