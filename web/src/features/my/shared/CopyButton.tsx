"use client";

import { useState } from "react";
import { useT } from "@/lib/i18n";

export function CopyButton({ text, label }: { text: string; label?: string }) {
  const t = useT();
  const [copied, setCopied] = useState(false);
  const copy = () => navigator.clipboard.writeText(text).then(() => {
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  });
  return (
    <button type="button" onClick={copy} className="shrink-0 text-sm text-muted hover:text-text">
      {copied ? t("common.copied") : label ?? t("common.copy")}
    </button>
  );
}
