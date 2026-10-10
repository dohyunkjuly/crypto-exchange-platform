"use client";

import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import { useT } from "@/lib/i18n";

export type ToastTone = "success" | "error" | "info";
/** The caller translates; this only shows. Nothing here reads a dictionary but its own close label. */
export type ToastInput = { tone: ToastTone; title: string; body?: string };
type Toast = ToastInput & { id: number };

const ShowToast = createContext<(toast: ToastInput) => void>(() => {});

/**
 * Announces what happened to an order.
 *
 * <p>Placing, amending and cancelling all answer 202 before the engine has decided, so the
 * request and its outcome are two separate moments seconds apart. A toast is how the second
 * one reaches a user who has already looked away from the form.
 */
export const useToast = () => useContext(ShowToast);

const DISMISS_MS = 6_000;
/** Older toasts fall off the top rather than filling the screen during a burst of fills. */
const MAX_VISIBLE = 4;

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const nextId = useRef(0);
  const dismiss = useCallback((id: number) => setToasts((ts) => ts.filter((t) => t.id !== id)), []);
  const show = useCallback((toast: ToastInput) => {
    setToasts((ts) => [...ts, { ...toast, id: nextId.current++ }].slice(-MAX_VISIBLE));
  }, []);

  return (
    <ShowToast.Provider value={show}>
      {children}
      {/* polite, not assertive: an order update is worth reading, never worth interrupting. */}
      <div aria-live="polite" className="pointer-events-none fixed right-4 top-4 z-50 flex w-[22rem]
                                        max-w-[calc(100vw-2rem)] flex-col gap-2">
        {toasts.map((t) => <ToastCard key={t.id} toast={t} onDismiss={() => dismiss(t.id)} />)}
      </div>
    </ShowToast.Provider>
  );
}

/** Floating over the page, so it carries a fill, a border and a shadow (docs/STYLE.md). */
function ToastCard({ toast, onDismiss }: { toast: Toast; onDismiss: () => void }) {
  const t = useT();
  useEffect(() => {
    const timer = setTimeout(onDismiss, DISMISS_MS);
    return () => clearTimeout(timer);
  }, [onDismiss]);
  return (
    <div role={toast.tone === "error" ? "alert" : "status"}
         className="pointer-events-auto flex items-start gap-3 rounded-lg border border-border bg-surface
                    p-3 shadow-lg">
      <ToneIcon tone={toast.tone} />
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium">{toast.title}</p>
        {toast.body && <p className="mt-1 text-xs text-muted">{toast.body}</p>}
      </div>
      <button type="button" onClick={onDismiss} aria-label={t("common.close")}
              className="text-dim transition-colors hover:text-text">
        <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.5"
             strokeLinecap="round" aria-hidden>
          <path d="M4 4l8 8M12 4l-8 8" />
        </svg>
      </button>
    </div>
  );
}

function ToneIcon({ tone }: { tone: ToastTone }) {
  const colour = tone === "success" ? "text-up" : tone === "error" ? "text-danger" : "text-muted";
  return (
    <svg viewBox="0 0 20 20" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.5"
         strokeLinecap="round" strokeLinejoin="round" className={`mt-0.5 shrink-0 ${colour}`} aria-hidden>
      <circle cx="10" cy="10" r="8" />
      {tone === "success" ? <path d="M6.5 10.5l2.5 2.5 4.5-5" /> : <path d="M10 6v5M10 13.5v.5" />}
    </svg>
  );
}
