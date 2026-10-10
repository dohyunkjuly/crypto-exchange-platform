/**
 * The symbols with a file in public/icons/coins/. Listed rather than probed, so an unknown
 * symbol renders the fallback immediately instead of a 404 and a broken-image flash.
 */
export const COIN_ICONS = new Set([
  "ADA", "AVAX", "BCH", "BTC", "DOGE", "DOT", "ETH", "LINK", "LTC", "SOL", "TRX", "USDT", "XRP",
]);

/** A coin's logo, or its first letter in a circle for an asset listed after these were added. */
export function CoinIcon({ symbol, size = 20 }: { symbol: string; size?: number }) {
  const upper = symbol.toUpperCase();
  if (COIN_ICONS.has(upper)) {
    return (
      // eslint-disable-next-line @next/next/no-img-element -- a 1 KB SVG gains nothing from the image pipeline
      <img src={`/icons/coins/${upper.toLowerCase()}.svg`} width={size} height={size} alt="" aria-hidden
           className="shrink-0 rounded-full" />
    );
  }
  return (
    <span aria-hidden style={{ width: size, height: size, fontSize: size * 0.5 }}
          className="inline-flex shrink-0 items-center justify-center rounded-full bg-surface-raised font-semibold text-muted">
      {upper.charAt(0)}
    </span>
  );
}
