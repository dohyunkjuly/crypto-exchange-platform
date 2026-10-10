"use client";

import { useState } from "react";
import { CodeInput, Dialog, Field, useBusy } from "./Dialog";

/**
 * One dialog for every write that is a single decision: it says what will happen, takes a reason
 * when the action wants one and a fresh authenticator code when admin-bff will ask for one, and
 * does the work once -- the button holds while the request is in flight, because a mutation is
 * never retried (docs/reference/frontend-session.md).
 *
 * @param tone "danger" for what takes something away (freeze, revoke, disable)
 */
export function Confirm({ title, body, confirmLabel, onConfirm, onClose, reasonLabel, code: needsCode = false, tone = "primary" }: {
  title: string; body: string; confirmLabel: string;
  onConfirm: (reason: string, code: string) => Promise<void>; onClose: () => void;
  reasonLabel?: string; code?: boolean; tone?: "primary" | "danger";
}) {
  const [reason, setReason] = useState("");
  const [code, setCode] = useState("");
  const { busy, error, run } = useBusy();
  const ready = (!reasonLabel || reason.trim().length > 0) && (!needsCode || code.length === 6);

  return (
    <Dialog title={title} onClose={onClose} onSubmit={() => ready && !busy && run(() => onConfirm(reason.trim(), code))}
            footer={<>
              <button type="button" className="btn" onClick={onClose}>Cancel</button>
              <button type="submit" className={`btn ${tone === "danger" ? "btn-danger" : "btn-primary"}`} disabled={!ready || busy}>{confirmLabel}</button>
            </>}>
      <p className="m-0 text-muted leading-relaxed">{body}</p>
      {reasonLabel && (
        <Field label={reasonLabel}>
          <input className="input" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={255} autoFocus />
        </Field>
      )}
      {needsCode && <CodeInput value={code} onChange={setCode} />}
      {error && <p role="alert" className="m-0 text-danger">{error}</p>}
    </Dialog>
  );
}
