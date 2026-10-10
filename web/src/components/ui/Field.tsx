"use client";

import { useId } from "react";

/** Error wired through aria-describedby/aria-invalid so colour is never the only signal. */
export function Field({
  label,
  error,
  hint,
  trailing,
  ...input
}: {
  label: string;
  error?: string | null;
  hint?: string;
  /** A small control on the label row -- "Forgot?" beside a password. */
  trailing?: React.ReactNode;
} & React.InputHTMLAttributes<HTMLInputElement>) {
  const id = useId();
  const describedBy = [error ? `${id}-error` : null, hint ? `${id}-hint` : null]
    .filter(Boolean)
    .join(" ");

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-baseline justify-between">
        <label htmlFor={id} className="text-xs font-medium text-muted">
          {label}
        </label>
        {trailing}
      </div>
      <input
        {...input}
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy || undefined}
        className="h-10 rounded-lg border border-border bg-surface-raised px-3 text-base text-text
                   placeholder:text-dim outline-none transition-colors
                   focus:border-border-strong aria-[invalid]:border-danger disabled:opacity-50"
      />
      {hint && !error && <p id={`${id}-hint`} className="text-xs text-dim">{hint}</p>}
      {error && <p id={`${id}-error`} className="text-xs text-danger">{error}</p>}
    </div>
  );
}
