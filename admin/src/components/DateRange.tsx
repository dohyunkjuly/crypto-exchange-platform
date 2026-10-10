/**
 * Two whole days in the admin's time zone, the last one included. Empty ends are open. One unit,
 * so on a phone the pair wraps together and shares the row.
 */
export function DateRange({ from, to, onChange }: {
  from: string; to: string; onChange: (next: { from?: string; to?: string }) => void;
}) {
  return (
    <span className="flex items-center gap-1.5 text-muted w-full sm:w-auto">
      <input type="date" aria-label="From" value={from} onChange={(e) => onChange({ from: e.target.value })} className="input flex-1 min-w-0 sm:flex-none" />
      to
      <input type="date" aria-label="To" value={to} onChange={(e) => onChange({ to: e.target.value })} className="input flex-1 min-w-0 sm:flex-none" />
    </span>
  );
}
