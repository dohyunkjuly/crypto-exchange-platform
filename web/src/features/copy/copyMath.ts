import { compare, isNegative, isValidDecimal, isZero, subtract } from "@/lib/money";

/**
 * What a leader may take out of the lead account while leading: everything above the minimum
 * balance, never below zero. Exact, so "100.3" held against a minimum of "100" is "0.3", not
 * 0.29999999999999716.
 */
export function withdrawable(held: string, minBalance: string): string {
  const left = subtract(held, minBalance);
  return isNegative(left) ? "0" : left;
}

/** Typed, well-formed, and more than the sender has. "" and garbage are neither. */
export function exceeds(amount: string, available: string): boolean {
  return isValidDecimal(amount) && compare(amount, available) > 0;
}

/** Typed, well-formed, and under a floor. */
export function below(amount: string, minimum: string): boolean {
  return isValidDecimal(amount) && compare(amount, minimum) < 0;
}

/** The colour a profit figure takes: nothing at zero, up or down by its sign. */
export function tone(value: string): "" | "text-up" | "text-down" {
  if (isZero(value)) return "";
  return isNegative(value) ? "text-down" : "text-up";
}

/**
 * Sort key for a leader's figure: larger first, except drawdown, where smaller is better.
 * A missing figure sorts last, not between. Exact: two leaders a satoshi apart stay in order.
 */
export function compareFigures(a: string | null | undefined, b: string | null | undefined, by: "maxDrawdown" | "other"): number {
  if (a == null && b == null) return 0;
  if (a == null) return 1;
  if (b == null) return -1;
  return by === "maxDrawdown" ? compare(a, b) : compare(b, a);
}
