/**
 * Nothing to list: one glyph and a short line, the same everywhere, so an empty page reads as
 * empty rather than as broken. The glyph is a sheet with a mark on it -- a record with nothing
 * in it -- drawn inline so it takes the text colour like the other icons.
 *
 * @param compact the trading screen's panels: a smaller glyph and the panel's type size
 * @param className the box the message sits in, where the list had a border to continue
 */
export function Empty({ children, compact = false, className = "" }: {
  children: React.ReactNode; compact?: boolean; className?: string;
}) {
  const size = compact ? 32 : 64;
  return (
    <div className={`flex flex-col items-center justify-center gap-4 text-center text-dim ${compact ? "py-6 text-xs" : "py-16 text-lg"} ${className}`}>
      <svg viewBox="0 0 48 48" width={size} height={size} fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round" aria-hidden className="opacity-70">
        <path d="M13 6h15l9 9v27H13z" />
        <path d="M28 6v9h9" />
        <rect x="18" y="20" width="12" height="9" rx="1" />
        <circle cx="35" cy="37" r="7" className="fill-bg" />
        <path d="M35 33.5v4M35 40.5v.01" />
      </svg>
      <p className="m-0">{children}</p>
    </div>
  );
}
