"use client";

import { Button } from "./Button";
import { Dialog } from "./Dialog";
import { useT } from "@/lib/i18n";

/** "Are you sure?" over a dimmed page: one question, Cancel and Confirm. Cancel is the safe default focus. */
export function ConfirmDialog({ title, message, onConfirm, onClose, pending }: {
  title: string; message: string; onConfirm: () => void; onClose: () => void; pending?: boolean;
}) {
  const t = useT();
  return (
    <Dialog title={title} onClose={onClose}>
      <p className="text-sm text-muted">{message}</p>
      <div className="mt-6 flex justify-end gap-2">
        <Button type="button" variant="secondary" onClick={onClose} disabled={pending} autoFocus>{t("common.cancel")}</Button>
        <Button type="button" onClick={onConfirm} pending={pending}>{t("common.confirm")}</Button>
      </div>
    </Dialog>
  );
}
