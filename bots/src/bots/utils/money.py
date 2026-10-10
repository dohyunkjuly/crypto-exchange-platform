"""Decimal arithmetic for prices and sizes.

Money is never a float here either (AGENTS.md): the API speaks decimal strings, and one
`0.1 + 0.2` is enough to put an order one tick off the tick grid and have it refused.
"""

from decimal import Decimal, localcontext

__all__ = ["D", "floor_to", "ceil_to", "round_to", "fmt", "scale_of"]

D = Decimal


def floor_to(value: Decimal, step: Decimal) -> Decimal:
    """The largest multiple of `step` at or below `value`. Sizes round this way: never more.

    Built from `%` rather than `//` or division. Decimal's `//` truncates toward zero instead
    of flooring, and dividing first would round the quotient to the context's precision before
    it was ever multiplied back. The remainder is exact.
    """
    if step <= 0:
        raise ValueError(f"step must be positive, got {step}")
    remainder = value % step
    # Decimal's % keeps the dividend's sign, so a negative is one whole step short of the floor.
    return value - (remainder + step if remainder < 0 else remainder)


def ceil_to(value: Decimal, step: Decimal) -> Decimal:
    """The smallest multiple of `step` at or above `value`."""
    if step <= 0:
        raise ValueError(f"step must be positive, got {step}")
    remainder = value % step
    if remainder > 0:
        return value + (step - remainder)
    return value - remainder


def round_to(value: Decimal, step: Decimal, *, up: bool) -> Decimal:
    return ceil_to(value, step) if up else floor_to(value, step)


def fmt(value: Decimal) -> str:
    """The wire form: plain digits, never scientific notation, no trailing zero noise.

    `str(Decimal("1E-8"))` is `"1E-8"`, which the API reads as a malformed number.
    """
    with localcontext() as ctx:
        ctx.prec = 40
        normalised = value.normalize()
        # normalize() turns 100 into 1E+2, which is the same problem from the other end.
        if normalised == normalised.to_integral_value():
            normalised = normalised.quantize(Decimal(1))
        return format(normalised, "f")


def scale_of(step: Decimal) -> int:
    """Decimal places in a tick or step, for formatting to exactly that precision."""
    exponent = step.normalize().as_tuple().exponent
    return max(0, -int(exponent))
