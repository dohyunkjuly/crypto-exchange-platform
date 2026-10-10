/** An instant as staff read it: local date and 24-hour time, sortable by eye. */
export function when(instant: string): string {
  const d = new Date(instant);
  return `${d.toLocaleDateString("sv-SE")} ${d.toLocaleTimeString([], { hour12: false })}`;
}

/** Local midnight of a yyyy-mm-dd day, `plus` days on, as an instant; "" for no day. */
export function dayStart(day: string | null, plus = 0): string {
  if (!day) return "";
  const [y, m, d] = day.split("-").map(Number);
  return new Date(y, m - 1, d + plus).toISOString();
}
