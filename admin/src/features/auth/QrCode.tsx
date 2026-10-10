"use client";

import { create } from "qrcode";

/**
 * Drawn from the code's matrix as SVG, synchronously, so there is no async render to wait on.
 * Always black on white with a quiet zone: scanners need that contrast whatever the theme.
 */
export function QrCode({ value, label, size = 176 }: { value: string; label: string; size?: number }) {
  const { modules } = create(value, { errorCorrectionLevel: "M" });
  const quiet = 4;
  const total = modules.size + quiet * 2;
  let path = "";
  for (let row = 0; row < modules.size; row++) {
    for (let col = 0; col < modules.size; col++) {
      if (modules.get(row, col)) path += `M${col + quiet} ${row + quiet}h1v1h-1z`;
    }
  }
  return (
    <svg role="img" aria-label={label} viewBox={`0 0 ${total} ${total}`} width={size} height={size}
         shapeRendering="crispEdges" className="shrink-0 rounded-lg">
      <rect width={total} height={total} fill="#fff" />
      <path d={path} fill="#000" />
    </svg>
  );
}
