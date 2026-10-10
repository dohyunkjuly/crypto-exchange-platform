"use client";

/**
 * A list of records in a bordered card. The columns render whether or not there are rows: a
 * header with "nothing yet" underneath says what this screen is about, where an empty box says
 * only that something is missing.
 *
 * @param right column indexes to align right -- every column of money or count
 * @param footer what sits under the rows, inside the card: the pager, a total
 */
export function Table({ head, rows, empty, right = [], footer }: {
  head: React.ReactNode[];
  rows: { key: string; cells: React.ReactNode[] }[];
  empty: string;
  right?: number[];
  footer?: React.ReactNode;
}) {
  const align = (i: number) => (right.includes(i) ? "text-right" : "text-left");
  return (
    <div className="card overflow-hidden">
      <div className="overflow-x-auto">
        <table className="w-full border-collapse whitespace-nowrap">
          <thead>
            <tr>
              {head.map((h, i) => (
                <th key={i} className={`bg-bg border-b border-border px-4 py-2 text-[12px] font-medium text-muted ${align(i)}`}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.key} className="group">
                {row.cells.map((cell, i) => (
                  <td key={i} className={`border-b border-border-muted px-4 py-2.5 text-[14px] align-middle group-last:border-b-0 group-hover:bg-bg ${align(i)}`}>{cell}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {rows.length === 0 && <p className="py-12 text-center text-muted">{empty}</p>}
      {footer && <div className="border-t border-border px-4 py-2.5 flex flex-wrap items-center justify-between gap-3 text-[13px] text-muted">{footer}</div>}
    </div>
  );
}
