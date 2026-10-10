"use client";

import Script from "next/script";
import { useEffect, useRef, useState } from "react";
import { GOOGLE_CLIENT_ID } from "@/lib/env";
import { signInWithGoogle, type LoginOutcome } from "./api";
import { FormError } from "@/components/ui/FormError";
import { useLocale, useT } from "@/lib/i18n";

const GIS_SRC = "https://accounts.google.com/gsi/client";

/**
 * Google's own rendered button. The browser does the whole exchange with Google and ends up
 * holding an ID token; web-bff verifies it and answers exactly like a password login -- so the
 * outcome is handed to the same handler, including the MFA branch. Google proving who someone
 * is does not prove they hold the second factor.
 *
 * Renders nothing without a client id: an operator may simply not offer Google, and the backend
 * refuses the flow anyway when its own id is unset.
 */
export function GoogleSignInButton({
  onOutcome,
  text = "continue_with",
}: {
  onOutcome: (outcome: LoginOutcome) => void;
  text?: "signin_with" | "signup_with" | "continue_with";
}) {
  const locale = useLocale();
  const slot = useRef<HTMLDivElement>(null);
  const [ready, setReady] = useState(() => typeof window !== "undefined" && !!window.google);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    if (!ready || !GOOGLE_CLIENT_ID || !slot.current || !window.google) return;
    window.google.accounts.id.initialize({
      client_id: GOOGLE_CLIENT_ID,
      callback: ({ credential }) =>
        signInWithGoogle(credential).then(onOutcome, setError),
    });
    // "outline" on both themes: a white button matches our own primary button, and it avoids
    // filled_black's near-black fill and grey border, which on a #000 page read as a broken edge.
    // Google caps the width at 400; asking for more leaves a visible gap beside the iframe.
    window.google.accounts.id.renderButton(slot.current, {
      theme: "outline",
      size: "large",
      shape: "rectangular",
      text,
      width: Math.min(slot.current.clientWidth, 400),
      logo_alignment: "center",
      locale,
    });
  }, [ready, onOutcome, text, locale]);

  if (!GOOGLE_CLIENT_ID) return null;

  return (
    <div className="flex flex-col gap-6">
      <Divider />
      <Script src={GIS_SRC} strategy="afterInteractive" onLoad={() => setReady(true)} />
      <div ref={slot} className="flex h-10 justify-center overflow-hidden rounded-lg" />
      <FormError error={error} />
    </div>
  );
}

/** "or" between the password form and the Google button. */
function Divider() {
  const t = useT();
  return (
    <div className="flex items-center gap-3 text-xs text-dim">
      <span className="h-px flex-1 bg-border" />
      {t("common.or")}
      <span className="h-px flex-1 bg-border" />
    </div>
  );
}
