"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { AdminApiError, api } from "@/lib/api";
import { EXCHANGE_NAME } from "@/lib/env";
import { QrCode } from "./QrCode";

type Step = "password" | "enrol" | "code";

/**
 * The login in the order admin-bff runs it: password, then either scan an authenticator
 * (first login only) or type its code. There is no "forgot password" and no recovery code:
 * a locked-out admin asks a SUPERADMIN.
 */
export function LoginFlow() {
  const router = useRouter();
  const [step, setStep] = useState<Step>("password");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [provisioningUri, setProvisioningUri] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submitPassword(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const { next } = await api<{ next: "CODE" | "ENROL" }>("/admin/auth/login", {
        method: "POST", body: { email, password },
      });
      setPassword("");
      if (next === "ENROL") {
        const enrolment = await api<{ provisioningUri: string }>("/admin/auth/totp/enrol", { method: "POST", body: {} });
        setProvisioningUri(enrolment.provisioningUri);
        setStep("enrol");
      } else {
        setStep("code");
      }
    } catch (e) {
      setError(e instanceof AdminApiError ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitCode(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api("/admin/auth/totp/verify", { method: "POST", body: { code } });
      router.replace("/");
    } catch (e) {
      setError(e instanceof AdminApiError ? e.message : String(e));
      setCode("");
    } finally {
      setBusy(false);
    }
  }

  const field = "input w-full h-9";
  const button = "btn btn-primary w-full h-9";

  return (
    <main className="flex-1 flex flex-col items-center pt-[14vh] px-6">
      <h1 className="m-0 mb-4 text-2xl font-light tracking-tight">Sign in to <span className="font-semibold">{EXCHANGE_NAME}</span></h1>
      <div className="card w-full max-w-[340px] p-5 space-y-4">

        {step === "password" && (
          <form onSubmit={submitPassword} className="space-y-3">
            <input className={field} type="email" placeholder="Email address" aria-label="Email address" autoComplete="username" value={email}
                   onChange={(e) => setEmail(e.target.value)} required autoFocus />
            <input className={field} type="password" placeholder="Password" aria-label="Password" autoComplete="current-password"
                   value={password} onChange={(e) => setPassword(e.target.value)} required />
            <button className={button} disabled={busy}>Continue</button>
          </form>
        )}

        {step === "enrol" && provisioningUri && (
          <div className="space-y-4">
            <p className="m-0 text-muted leading-relaxed">
              First login. Scan this with an authenticator app, then type the code it shows. There is no
              recovery code: if you lose the authenticator, a superadmin resets it.
            </p>
            <div className="flex justify-center"><QrCode value={provisioningUri} label="Authenticator enrolment" /></div>
            <CodeForm code={code} setCode={setCode} onSubmit={submitCode} busy={busy} />
          </div>
        )}

        {step === "code" && (
          <div className="space-y-4">
            <p className="m-0 text-muted leading-relaxed">The code from your authenticator.</p>
            <CodeForm code={code} setCode={setCode} onSubmit={submitCode} busy={busy} />
          </div>
        )}

        {error && <p role="alert" className="m-0 rounded-md border border-[var(--danger-border)] bg-danger-bg px-3 py-2 text-danger">{error}</p>}
      </div>
      <p className="mt-4 text-[12px] text-muted">Staff only. Every sign-in is recorded.</p>
    </main>
  );
}

function CodeForm({ code, setCode, onSubmit, busy }: {
  code: string; setCode: (c: string) => void; onSubmit: (e: FormEvent) => void; busy: boolean;
}) {
  return (
    <form onSubmit={onSubmit} className="space-y-3">
      <input className="input w-full h-9 font-mono tracking-[0.4em] text-center"
             inputMode="numeric" pattern="\d{6}" maxLength={6} placeholder="000000" value={code}
             onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} required autoFocus />
      <button className="btn btn-primary w-full h-9"
              disabled={busy || code.length !== 6}>Log in</button>
    </form>
  );
}
