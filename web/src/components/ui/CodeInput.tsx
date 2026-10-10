"use client";

import { useId, useRef, useState } from "react";
import { useT } from "@/lib/i18n";

/**
 * One box per digit for the six-digit codes every email flow uses. Typing advances, Backspace
 * on an empty box retreats, and a paste of the whole code fills every box from wherever it
 * lands -- that last one matters most, since most codes arrive by copy from a mail client.
 *
 * Submits through a hidden input named `name`, so a form reads it with FormData exactly as it
 * would a plain field.
 */
export function CodeInput({
  name = "code",
  length = 6,
  label,
  autoFocus,
  disabled,
  onComplete,
}: {
  name?: string;
  length?: number;
  /** Defaults to the localized "Code". */
  label?: string;
  autoFocus?: boolean;
  disabled?: boolean;
  /** Fires once each time the last box is filled -- a form may submit on it. */
  onComplete?: (code: string) => void;
}) {
  const t = useT();
  const id = useId();
  const [digits, setDigits] = useState<string[]>(() => Array(length).fill(""));
  const boxes = useRef<(HTMLInputElement | null)[]>([]);
  const value = digits.join("");

  const focus = (i: number) => boxes.current[Math.max(0, Math.min(length - 1, i))]?.focus();

  const commit = (next: string[]) => {
    setDigits(next);
    const joined = next.join("");
    if (joined.length === length) onComplete?.(joined);
  };

  /** Writes `text` starting at box `from`; used by both typing and pasting. */
  const fill = (from: number, text: string) => {
    const clean = text.replace(/\D/g, "");
    if (!clean) return;
    const next = [...digits];
    let i = from;
    for (const ch of clean) {
      if (i >= length) break;
      next[i++] = ch;
    }
    commit(next);
    focus(i >= length ? length - 1 : i);
  };

  return (
    <fieldset className="flex flex-col gap-1.5" disabled={disabled}>
      <legend className="mb-1.5 text-xs font-medium text-muted">{label ?? t("common.code")}</legend>
      <div className="flex justify-between gap-2">
        {digits.map((d, i) => (
          <input
            key={i}
            ref={(el) => { boxes.current[i] = el; }}
            id={i === 0 ? id : undefined}
            aria-label={t("common.digit", { i: i + 1, n: length })}
            inputMode="numeric"
            autoComplete={i === 0 ? "one-time-code" : "off"}
            autoFocus={autoFocus && i === 0}
            maxLength={length}
            value={d}
            onChange={(e) => {
              // maxLength allows a multi-character value here so an OS autofill of the whole
              // code lands in one event; take everything after what was already in the box.
              const typed = e.target.value.slice(d ? 1 : 0) || e.target.value;
              if (!typed) { const n = [...digits]; n[i] = ""; commit(n); return; }
              fill(i, typed);
            }}
            onKeyDown={(e) => {
              if (e.key === "Backspace" && !d && i > 0) {
                const n = [...digits]; n[i - 1] = ""; commit(n); focus(i - 1); e.preventDefault();
              } else if (e.key === "ArrowLeft") { focus(i - 1); e.preventDefault(); }
              else if (e.key === "ArrowRight") { focus(i + 1); e.preventDefault(); }
            }}
            onPaste={(e) => { e.preventDefault(); fill(i, e.clipboardData.getData("text")); }}
            onFocus={(e) => e.target.select()}
            className="num h-12 w-full rounded-lg border border-border bg-surface-raised text-center text-lg
                       text-text outline-none transition-colors focus:border-border-strong
                       disabled:opacity-50"
          />
        ))}
      </div>
      <input type="hidden" name={name} value={value} />
    </fieldset>
  );
}
