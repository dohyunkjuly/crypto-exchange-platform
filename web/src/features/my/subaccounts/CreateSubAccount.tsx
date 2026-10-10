"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { Field } from "@/components/ui/Field";
import { FormError } from "@/components/ui/FormError";
import { useT } from "@/lib/i18n";
import { createSubAccount } from "../shared/api";

/** One field, so a dialog rather than a page: the email is assigned and there is no password,
 *  and the list behind it is where the new sub is wanted. */
export function CreateSubAccount({ onClose }: { onClose: () => void }) {
  const t = useT();
  const queryClient = useQueryClient();
  const [label, setLabel] = useState("");
  const create = useMutation({
    mutationFn: createSubAccount,
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ["sub-accounts"] }); onClose(); },
  });

  return (
    <Dialog title={t("subAccounts.create")} onClose={onClose}>
      <form className="flex flex-col gap-5"
            onSubmit={(e) => { e.preventDefault(); create.mutate({ label: label.trim() }); }}>
        <Field label={t("subAccounts.label")} name="label" maxLength={64} required autoFocus
               value={label} onChange={(e) => setLabel(e.target.value)} />
        <FormError error={create.error} />
        <div className="flex gap-2">
          <Button type="submit" pending={create.isPending}>{t("subAccounts.create")}</Button>
          <Button type="button" variant="ghost" onClick={onClose}>{t("common.cancel")}</Button>
        </div>
      </form>
    </Dialog>
  );
}
