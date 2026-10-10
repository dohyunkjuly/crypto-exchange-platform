/**
 * Arithmetic on decimal strings, via BigInt.
 *
 * Prices, quantities and balances arrive from web-bff as strings and stay strings. Floats
 * are not an option: `0.1 + 0.2 !== 0.3`, a double silently loses precision past 2^53, and
 * a rounding error in a balance is not a bug anyone can apologise their way out of. So
 * every value here is `{ units: bigint, scale: number }` -- an exact integer and where the
 * decimal point sits -- and comes back out as a string.
 *
 * Scale is never truncated by an operation: adding "1.5" and "2.25" gives "3.75", not
 * "3.8". Rounding happens only where a caller asks for it, by name.
 */

type Decimal = { units: bigint; scale: number };

/** Optional leading sign, digits, optional fraction. No exponents -- the backend never
 *  sends them and accepting "1e9" as a price would be generous in the wrong direction. */
const DECIMAL = /^-?\d+(\.\d+)?$/;

export function isValidDecimal(value: string): boolean {
  return DECIMAL.test(value.trim());
}

function parse(value: string): Decimal {
  const trimmed = value.trim();
  if (!DECIMAL.test(trimmed)) {
    throw new Error(`not a decimal: "${value}"`);
  }
  const [whole, fraction = ""] = trimmed.split(".");
  // "-0.5" -> whole is "-0", and BigInt("-0" + "5") is -5. Concatenation keeps the sign
  // because it stays attached to the leading digits.
  return { units: BigInt(whole + fraction), scale: fraction.length };
}

/** Brings two values to a common scale so their units can be compared or summed. */
function align(a: Decimal, b: Decimal): [bigint, bigint, number] {
  const scale = Math.max(a.scale, b.scale);
  return [
    a.units * 10n ** BigInt(scale - a.scale),
    b.units * 10n ** BigInt(scale - b.scale),
    scale,
  ];
}

function render({ units, scale }: Decimal): string {
  if (scale === 0) {
    return units.toString();
  }
  const negative = units < 0n;
  const digits = (negative ? -units : units).toString().padStart(scale + 1, "0");
  const whole = digits.slice(0, digits.length - scale);
  const fraction = digits.slice(digits.length - scale);
  return `${negative ? "-" : ""}${whole}.${fraction}`;
}

/** -1, 0 or 1. The one function everything else is built on: sorting an order book and
 *  deciding "can they afford this" are both comparisons, and both must be exact. */
export function compare(a: string, b: string): number {
  const [left, right] = align(parse(a), parse(b));
  return left < right ? -1 : left > right ? 1 : 0;
}

export function add(a: string, b: string): string {
  const [left, right, scale] = align(parse(a), parse(b));
  return render({ units: left + right, scale });
}

export function subtract(a: string, b: string): string {
  const [left, right, scale] = align(parse(a), parse(b));
  return render({ units: left - right, scale });
}

/** Exact: scales add, so "0.1" x "0.2" is "0.02" and nothing is lost. Notional is this --
 *  price times quantity -- and it must agree with what the backend charged. */
export function multiply(a: string, b: string): string {
  const left = parse(a);
  const right = parse(b);
  return render({ units: left.units * right.units, scale: left.scale + right.scale });
}

export function isZero(value: string): boolean {
  return parse(value).units === 0n;
}

export function isNegative(value: string): boolean {
  return parse(value).units < 0n;
}

/**
 * Snaps a value onto a multiple of `step`.
 *
 * `"down"` and `"up"` are both needed and are not interchangeable: an order book groups
 * bids **down** and asks **up**, both away from the spread, so a grouped level never claims
 * a better price than really exists. Tick-size snapping on an order form rounds down, so a
 * user is never talked into a higher bid than they typed.
 */
export function roundToStep(value: string, step: string, direction: "down" | "up"): string {
  const stepDecimal = parse(step);
  if (stepDecimal.units <= 0n) {
    throw new Error(`step must be positive, got "${step}"`);
  }
  const [units, stepUnits] = align(parse(value), stepDecimal);

  // BigInt division truncates toward zero, which is the wrong direction for negatives --
  // floor and ceil have to be spelled out rather than assumed.
  let steps = units / stepUnits;
  const remainder = units % stepUnits;
  if (remainder !== 0n) {
    if (direction === "down" && units < 0n) {
      steps -= 1n;
    } else if (direction === "up" && units > 0n) {
      steps += 1n;
    }
  }

  // Rendered at the STEP's scale, not the input's. A price snapped to a step of "1" is
  // "63250", not "63250.00" -- the step is what says how precise the answer can be, and
  // the result is an exact multiple of it so nothing is lost. Keeping the input's scale
  // would make a ladder grouped at 50 still print two meaningless decimal places.
  return render({ units: steps * stepDecimal.units, scale: stepDecimal.scale });
}

/** Exactly `decimals` places, for a column that has to line up. Rounds half away from
 *  zero, the rule a person reading a price expects. */
export function toFixed(value: string, decimals: number): string {
  const parsed = parse(value);
  if (decimals >= parsed.scale) {
    return render({
      units: parsed.units * 10n ** BigInt(decimals - parsed.scale),
      scale: decimals,
    });
  }
  const divisor = 10n ** BigInt(parsed.scale - decimals);
  const negative = parsed.units < 0n;
  const magnitude = negative ? -parsed.units : parsed.units;
  let quotient = magnitude / divisor;
  if ((magnitude % divisor) * 2n >= divisor) {
    quotient += 1n;
  }
  return render({ units: negative ? -quotient : quotient, scale: decimals });
}

/** How many decimal places a value carries -- a symbol's tick size is how the UI learns
 *  how many to render for its price column. */
export function scaleOf(value: string): number {
  return parse(value).scale;
}

/**
 * `a / b` to `decimals` places, rounded down. Division is the one operation that cannot stay
 * exact, so the caller names the precision -- an order form divides a budget by a price and then
 * snaps to the quantity step anyway. Throws on a zero divisor rather than answering Infinity.
 */
export function divide(a: string, b: string, decimals: number): string {
  const left = parse(a);
  const right = parse(b);
  if (right.units === 0n) {
    throw new Error("division by zero");
  }
  // Lift the dividend so the integer quotient carries `decimals` fraction digits.
  const lifted = left.units * 10n ** BigInt(decimals + right.scale);
  const quotient = lifted / (right.units * 10n ** BigInt(left.scale));
  return render({ units: quotient, scale: decimals });
}
