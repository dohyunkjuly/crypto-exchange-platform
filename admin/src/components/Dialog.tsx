"use client";

import { useEffect, useState } from "react";

/**
 * The frame every dialog in the console shares: a title, the body, and the buttons along the
 * bottom. Escape and a click outside both close it -- neither ever confirms anything.
 */
export function Dialog({ title, onClose, children, footer, onSubmit }: {
  title: string; onClose: () => void; children: React.ReactNode; footer: React.ReactNode;
  /** Makes the dialog a form, so Enter submits it the way a person expects. */
  onSubmit?: () => void;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  const body = (
    <>
      <div className="px-5 py-3.5 border-b border-border bg-bg rounded-t-lg">
        <h2 id="dialog-title" className="m-0 text-[15px] font-semibold">{title}</h2>
      </div>
      <div className="px-5 py-4 flex flex-col gap-4">{children}</div>
      <div className="px-5 py-3 border-t border-border flex justify-end gap-2">{footer}</div>
    </>
  );
  const frame = "w-full max-w-md card shadow-[0_8px_24px_rgba(140,149,159,0.2)]";
  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto px-4 pt-[8vh] pb-4 sm:pt-[12vh] bg-[rgba(31,35,40,0.35)]" onClick={onClose}>
      {onSubmit
        ? <form role="dialog" aria-modal aria-labelledby="dialog-title" className={frame} onClick={(e) => e.stopPropagation()}
                onSubmit={(e) => { e.preventDefault(); onSubmit(); }}>{body}</form>
        : <div role="dialog" aria-modal aria-labelledby="dialog-title" className={frame} onClick={(e) => e.stopPropagation()}>{body}</div>}
    </div>
  );
}

/** A labelled field inside a dialog or a form: the label above, the control under it. */
export function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1.5">
      <span className="text-[13px] font-medium">{label}</span>
      {children}
    </label>
  );
}

/** Six digits from the authenticator, for a write that needs a fresh one. */
export function CodeInput({ value, onChange, label = "Your authenticator code" }: {
  value: string; onChange: (code: string) => void; label?: string;
}) {
  return (
    <Field label={label}>
      <input className="input w-36 font-mono tracking-[0.3em]" inputMode="numeric" autoComplete="one-time-code"
             value={value} onChange={(e) => onChange(e.target.value.replace(/\D/g, "").slice(0, 6))} />
    </Field>
  );
}

/** A submit that holds itself down while its request is in flight: a mutation is never retried. */
export function useBusy() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const run = async (work: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await work();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Something went wrong");
      setBusy(false);
    }
  };
  return { busy, error, run };
}
