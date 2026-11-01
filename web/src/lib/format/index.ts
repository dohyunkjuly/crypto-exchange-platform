import { toFixed } from "@/lib/money";

/** Exact: fixed decimals on the decimal string, no float. */
export function price(value: string, decimals: number): string {
  return withGrouping(toFixed(value, decimals));
}

/** Grouped at the precision the backend sent, for a column whose scale is the asset's own. */
export function grouped(value: string): string {
  return withGrouping(value);
}

/** A profit or loss: "+" on a gain, "-" kept on a loss, nothing on zero. */
export function signed(value: string, decimals: number): string {
  const fixed = price(value, decimals);
  return fixed.startsWith("-") || /^[0,.]*$/.test(fixed) ? fixed : "+" + fixed;
}

/** Signed percent with two places, from the backend's "2.50" string. */
export function percent(value: string): string {
  return (value.startsWith("-") ? "" : "+") + value + "%";
}

/**
 * "1.2M" style. This is the one place `parseFloat` is allowed (AGENTS.md): the result is
 * rendered immediately and never compared, totalled or sent anywhere.
 */
export function compact(value: string): string {
  const n = parseFloat(value);
  if (!Number.isFinite(n)) return value;
  return new Intl.NumberFormat("en", { notation: "compact", maximumFractionDigits: 2 }).format(n);
}

function withGrouping(fixed: string): string {
  const [whole, frac] = fixed.split(".");
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return frac === undefined ? grouped : `${grouped}.${frac}`;
}
