/** One labelled value in a record's header grid: a customer file, an order. Goes inside a `dl`.
 *  Cut short on a wide screen, where the grid is one row; wrapped on a phone, where nothing is beside it. */
export function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5 min-w-0">
      <dt className="text-[12px] text-muted">{label}</dt>
      <dd className="m-0 break-words sm:truncate">{children}</dd>
    </div>
  );
}
