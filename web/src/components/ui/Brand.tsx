import { EXCHANGE_NAME } from "@/lib/env";

/**
 * Both theme variants are rendered and CSS shows one, keyed on `data-theme` (globals.css
 * `.brand-*`). Choosing in JS would need the theme at render time and flash on hydration.
 * Plain <img>, not next/image: the files are the operator's own SVGs (public/brand/README.md),
 * of any aspect ratio, and the image pipeline has nothing to add to an SVG.
 */
/* eslint-disable @next/next/no-img-element */
export function Mark({ size = 24, className = "" }: { size?: number; className?: string }) {
  return (
    <span className={`inline-block shrink-0 ${className}`} style={{ width: size, height: size }}>
      <img className="brand-dark" src="/brand/mark-dark.svg" width={size} height={size} alt={EXCHANGE_NAME} />
      <img className="brand-light" src="/brand/mark-light.svg" width={size} height={size} alt={EXCHANGE_NAME} />
    </span>
  );
}

/** Sized by height; the file's own ratio sets the width. */
export function Wordmark({ height = 20, className = "" }: { height?: number; className?: string }) {
  return (
    <span className={`inline-flex shrink-0 ${className}`} style={{ height }}>
      <img className="brand-dark" src="/brand/wordmark-dark.svg" style={{ height, width: "auto" }} alt={EXCHANGE_NAME} />
      <img className="brand-light" src="/brand/wordmark-light.svg" style={{ height, width: "auto" }} alt={EXCHANGE_NAME} />
    </span>
  );
}
