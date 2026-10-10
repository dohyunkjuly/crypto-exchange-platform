"use client";

import { useId, useState } from "react";
import { useT } from "@/lib/i18n";

/** A password input with a show/hide eye, styled as Field. `children` sit under the input. */
export function PasswordField({ label, children, ...input }: {
  label: string; children?: React.ReactNode;
} & Omit<React.InputHTMLAttributes<HTMLInputElement>, "type">) {
  const t = useT();
  const id = useId();
  const [shown, setShown] = useState(false);
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-xs font-medium text-muted">{label}</label>
      <div className="relative">
        <input {...input} id={id} type={shown ? "text" : "password"}
               className="h-10 w-full rounded-lg border border-border bg-surface-raised px-3 pr-10 text-base text-text
                          placeholder:text-dim outline-none transition-colors focus:border-border-strong disabled:opacity-50" />
        <button type="button" onClick={() => setShown((s) => !s)} aria-pressed={shown}
                aria-label={shown ? t("common.hidePassword") : t("common.showPassword")}
                className="absolute inset-y-0 right-0 flex w-10 items-center justify-center text-dim hover:text-text">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
            <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z" />
            <circle cx="12" cy="12" r="3" />
            {!shown && <path d="M4 4l16 16" />}
          </svg>
        </button>
      </div>
      {children}
    </div>
  );
}
