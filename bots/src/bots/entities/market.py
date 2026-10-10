"""A market's filters, and how to make an order satisfy them.

Every rejection here is one the exchange would have made anyway. Getting it right locally is
not politeness: a bot that sends unroundable prices spends its whole order budget on 400s and
never puts anything on the book.
"""

from dataclasses import dataclass
from decimal import Decimal

from ..utils.money import D, ceil_to, floor_to

__all__ = ["Market"]


@dataclass(frozen=True, slots=True)
class Market:
    symbol: str
    base: str
    quote: str
    status: str
    price_tick: Decimal
    qty_step: Decimal
    min_qty: Decimal
    max_qty: Decimal
    min_notional: Decimal
    max_notional: Decimal
    market_min_qty: Decimal
    market_max_qty: Decimal
    min_quote_qty: Decimal
    max_quote_qty: Decimal
    market_orders_enabled: bool
    stop_orders_enabled: bool

    @classmethod
    def from_api(cls, raw: dict) -> "Market":
        return cls(
            symbol=raw["symbol"],
            base=raw["baseAsset"],
            quote=raw["quoteAsset"],
            status=raw["status"],
            price_tick=D(raw["priceTick"]),
            qty_step=D(raw["qtyStep"]),
            min_qty=D(raw["minQty"]),
            max_qty=D(raw["maxQty"]),
            min_notional=D(raw["minNotional"]),
            max_notional=D(raw["maxNotional"]),
            market_min_qty=D(raw["marketMinQty"]),
            market_max_qty=D(raw["marketMaxQty"]),
            min_quote_qty=D(raw["minQuoteQty"]),
            max_quote_qty=D(raw["maxQuoteQty"]),
            market_orders_enabled=raw["marketOrdersEnabled"],
            stop_orders_enabled=raw["stopOrdersEnabled"],
        )

    @classmethod
    def all_from_api(cls, payload: dict) -> dict[str, "Market"]:
        """`GET /v1/markets/info` -> the markets by symbol. Non-trading ones are kept, not hidden."""
        return {raw["symbol"]: cls.from_api(raw) for raw in payload["symbols"]}

    @property
    def trading(self) -> bool:
        return self.status == "TRADING"

    def price(self, value: Decimal, *, side: str) -> Decimal:
        """On the tick grid, rounded to the side that stays passive.

        A buy rounds down and a sell rounds up, so rounding never turns a quote meant to rest
        into one that crosses -- which for a post-only order is a rejection, and for a plain
        limit is a fill at a price nobody chose.
        """
        return floor_to(value, self.price_tick) if side == "BUY" else ceil_to(value, self.price_tick)

    def quantity(self, value: Decimal, *, price: Decimal | None = None, market: bool = False) -> Decimal:
        """On the step grid and inside the filters, never rounding up into more risk.

        The one exception is `min_notional`: an order too small to be legal is worth nothing
        resting, so it is lifted to the smallest size that is. The caller checks affordability.
        """
        low = self.market_min_qty if market else self.min_qty
        high = self.market_max_qty if market else self.max_qty

        quantity = max(floor_to(value, self.qty_step), low)
        if price is not None and price > 0 and quantity * price < self.min_notional:
            quantity = ceil_to(self.min_notional / price, self.qty_step)
        return min(quantity, floor_to(high, self.qty_step))

    def check(self, price: Decimal | None, quantity: Decimal, *, market: bool = False) -> str | None:
        """Why the exchange would refuse this order, or None. The same order the filters run in."""
        if not self.trading:
            return f"{self.symbol} is {self.status}"
        if market and not self.market_orders_enabled:
            return f"{self.symbol} takes no market orders"

        low = self.market_min_qty if market else self.min_qty
        high = self.market_max_qty if market else self.max_qty
        if quantity < low:
            return f"quantity {quantity} below {low}"
        if quantity > high:
            return f"quantity {quantity} above {high}"
        if quantity % self.qty_step != 0:
            return f"quantity {quantity} is not a multiple of {self.qty_step}"

        if price is None:
            return None
        if price <= 0:
            return f"price {price} is not positive"
        if price % self.price_tick != 0:
            return f"price {price} is not a multiple of {self.price_tick}"

        notional = price * quantity
        if notional < self.min_notional:
            return f"notional {notional} below {self.min_notional}"
        if notional > self.max_notional:
            return f"notional {notional} above {self.max_notional}"
        return None
