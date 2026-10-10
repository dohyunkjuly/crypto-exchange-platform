"""The exchange's order vocabulary, as the wire spells it.

`StrEnum`, so a member *is* its wire value: `Side.BUY == "BUY"`, and it goes into a request
body or a query string without a `.value`. docs/reference/order-types.md has the rules.
"""

import time
from dataclasses import dataclass, field
from decimal import Decimal
from enum import StrEnum

from ..utils.money import D

__all__ = ["Side", "OrderType", "TimeInForce", "OrderStatus", "CancelScope", "SelfTradePrevention",
           "Order"]


class Side(StrEnum):
    BUY = "BUY"
    SELL = "SELL"


class OrderType(StrEnum):
    LIMIT = "LIMIT"
    MARKET = "MARKET"
    STOP_LOSS = "STOP_LOSS"
    STOP_LOSS_LIMIT = "STOP_LOSS_LIMIT"
    TAKE_PROFIT = "TAKE_PROFIT"
    TAKE_PROFIT_LIMIT = "TAKE_PROFIT_LIMIT"
    # Refused outright if it would cross, so it can only ever make.
    LIMIT_MAKER = "LIMIT_MAKER"


class TimeInForce(StrEnum):
    GTC = "GTC"
    IOC = "IOC"
    FOK = "FOK"


class OrderStatus(StrEnum):
    PENDING_TRIGGER = "PENDING_TRIGGER"
    NEW = "NEW"
    PARTIALLY_FILLED = "PARTIALLY_FILLED"
    FILLED = "FILLED"
    PENDING_CANCEL = "PENDING_CANCEL"
    CANCELED = "CANCELED"
    REJECTED = "REJECTED"
    EXPIRED = "EXPIRED"

    @property
    def is_open(self) -> bool:
        return self in (OrderStatus.PENDING_TRIGGER, OrderStatus.NEW,
                        OrderStatus.PARTIALLY_FILLED, OrderStatus.PENDING_CANCEL)


class CancelScope(StrEnum):
    """Which open orders a cancel-all reaches."""
    ALL = "ALL"
    BOOK = "BOOK"
    TRIGGERS = "TRIGGERS"


class SelfTradePrevention(StrEnum):
    EXPIRE_TAKER = "EXPIRE_TAKER"
    EXPIRE_MAKER = "EXPIRE_MAKER"
    EXPIRE_BOTH = "EXPIRE_BOTH"


@dataclass(slots=True)
class Order:
    """An open order, as much of it as a bot steers by."""

    order_id: str
    symbol: str
    side: Side
    price: Decimal | None
    remaining: Decimal
    status: OrderStatus
    # When this process first held it (monotonic), not when the exchange made it.
    held_since: float = field(default_factory=time.monotonic)

    @classmethod
    def from_api(cls, raw: dict) -> "Order":
        """From `GET /v1/orders/open`, or the `order` inside an `orderUpdate`: one shape."""
        return cls(order_id=raw["orderId"], symbol=raw["symbol"], side=Side(raw["side"]),
                   price=D(raw["price"]) if raw.get("price") is not None else None,
                   remaining=D(raw.get("remainingQuantity") or raw.get("quantity") or "0"),
                   status=OrderStatus(raw["status"]))
