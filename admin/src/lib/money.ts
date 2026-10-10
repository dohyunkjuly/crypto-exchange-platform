/**
 * Amounts arrive as decimal strings and are rendered as decimal strings. Never parseFloat:
 * 0.1 + 0.2 is the reason money is a string on the wire (docs/reference/money.md), and a
 * balance sheet is the last place to start rounding it in the browser.
 */
export function amount(decimal: string, places: number): string {
  const negative = decimal.startsWith("-");
  const [whole, fraction = ""] = (negative ? decimal.slice(1) : decimal).split(".");
  const padded = places === 0 ? "" : `.${fraction.padEnd(places, "0").slice(0, places)}`;
  return `${negative ? "-" : ""}${grouped(whole)}${padded}`;
}

/** Thousands separators, left to right, on the integer part only. */
function grouped(whole: string): string {
  return whole.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
}

/** True when a decimal string is zero, whatever it is written as: "0", "0.00", "-0". */
export function isZero(decimal: string): boolean {
  return /^-?0*(\.0*)?$/.test(decimal);
}

/** Every digit the server sent, grouped. For amounts whose asset's display places are not at hand. */
export function exact(decimal: string): string {
  return amount(decimal, decimal.split(".")[1]?.length ?? 0);
}
