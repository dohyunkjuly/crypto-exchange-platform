"use client";

import { format, type Vars } from "./format";
import { createContext, useContext } from "react";
import en from "./messages/en.json";
import { DEFAULT_LOCALE, type Locale } from "./locales";

export type Messages = typeof en;
/** "login.title" -- every leaf key, typed, so a typo is a compile error rather than a blank. */
export type MessageKey = Leaves<Messages>;
type Leaves<T, P extends string = ""> = {
  [K in keyof T & string]: T[K] extends string ? `${P}${K}` : Leaves<T[K], `${P}${K}.`>;
}[keyof T & string];

export type T = (key: MessageKey, vars?: Vars) => string;

const Ctx = createContext<{ locale: Locale; messages: Messages }>({ locale: DEFAULT_LOCALE, messages: en });

/** Mounted by the [lang] layout with that segment's dictionary. Absent (in tests), English. */
export function I18nProvider({ locale, messages, children }: { locale: Locale; messages: Messages; children: React.ReactNode }) {
  return <Ctx.Provider value={{ locale, messages }}>{children}</Ctx.Provider>;
}

export function useLocale(): Locale {
  return useContext(Ctx).locale;
}

export function useT(): T {
  const { messages } = useContext(Ctx);
  return (key, vars) => format(lookup(messages, key) ?? lookup(en, key) ?? key, vars);
}

/** For strings keyed by a runtime value -- an error code -- where the key may not exist. */
export function useTOptional(): (key: string, vars?: Vars) => string | null {
  const { messages } = useContext(Ctx);
  return (key, vars) => {
    const s = lookup(messages, key) ?? lookup(en, key);
    return s === undefined ? null : format(s, vars);
  };
}

function lookup(messages: object, key: string): string | undefined {
  let node: unknown = messages;
  for (const part of key.split(".")) {
    if (typeof node !== "object" || node === null || !(part in node)) return undefined;
    node = (node as Record<string, unknown>)[part];
  }
  return typeof node === "string" ? node : undefined;
}

