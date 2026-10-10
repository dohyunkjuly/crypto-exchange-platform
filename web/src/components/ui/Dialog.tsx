"use client";

import { useEffect, useId } from "react";
import { useT } from "@/lib/i18n";

/**
 * A small pop-up: a floating surface over a dimmed page (docs/STYLE.md "Surfaces").
 * Escape and a click outside both close it; focus starts wherever a child asks for autoFocus.
 */
export function Dialog({ title, onClose, children }: { title: string; onClose: () => void; children: React.ReactNode }) {
  const t = useT();
  const id = useId();
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
         onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div role="dialog" aria-modal="true" aria-labelledby={id}
           className="w-full max-w-sm rounded-lg border border-border bg-surface p-6 shadow-lg">
        <div className="mb-5 flex items-center justify-between gap-4">
          <h2 id={id} className="text-base font-semibold">{title}</h2>
          <button type="button" aria-label={t("common.close")} onClick={onClose} className="text-muted hover:text-text">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden>
              <path d="M6 6l12 12M18 6 6 18" />
            </svg>
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}
