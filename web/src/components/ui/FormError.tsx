"use client";

import { BffError, BffUnreachableError } from "@/lib/bff/errors";
import { useT, useTOptional } from "@/lib/i18n";

/** Turns a failed request into words, in the viewer's language. Backend messages are English
 *  copy, so a known code is looked up in the dictionary first and the backend text is the
 *  fallback for a code we have not translated. A 401 renders nothing: the gate is redirecting. */
export function useDescribe(): (error: unknown) => string | null {
  const t = useT();
  const tOpt = useTOptional();
  return (error) => {
    if (!error) return null;
    if (error instanceof BffUnreachableError) return t("common.unreachable");
    if (error instanceof BffError) {
      if (error.isUnauthenticated) return null;
      return tOpt(`errors.${error.code}`) ?? error.message;
    }
    return t("common.generic");
  };
}

export function FormError({ error }: { error: unknown }) {
  const message = useDescribe()(error);
  if (!message) return null;
  return (
    <p role="alert" className="rounded-md border border-danger/40 bg-down-soft px-3 py-2 text-sm text-danger">
      {message}
    </p>
  );
}
