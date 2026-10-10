/** One headline number in a card: what it is, the number, and a line of context under it. */
export function Stat({ label, value, note, tone }: {
  label: string; value: React.ReactNode; note?: React.ReactNode; tone?: "warn";
}) {
  return (
    <div className="card px-[18px] py-4">
      <div className="stat-label">{label}</div>
      <div className={`stat-value ${tone === "warn" ? "text-warning" : ""}`}>{value}</div>
      {note && <div className="mt-1 text-[12px] text-muted">{note}</div>}
    </div>
  );
}
