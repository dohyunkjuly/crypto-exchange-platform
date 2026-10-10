/** A status pill. `muted` is the "not set up" look: present, but not something to read as done. */
export function Badge({ muted = false, children }: { muted?: boolean; children: React.ReactNode }) {
  return (
    <span className={`whitespace-nowrap rounded-full border px-2 py-0.5 text-xs
                      ${muted ? "border-border text-dim" : "border-border-strong text-text"}`}>
      {children}
    </span>
  );
}
