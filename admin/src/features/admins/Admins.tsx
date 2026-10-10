"use client";

import { useCallback, useEffect, useState } from "react";
import { Confirm } from "@/components/Confirm";
import { CodeInput, Dialog, Field, useBusy } from "@/components/Dialog";
import { Table } from "@/components/Table";
import { AdminApiError } from "@/lib/api";
import { useAdmin } from "@/lib/session";
import { changeRole, createAdmin, disableAdmin, fetchAdmins, resetTotp, type Role, type Staff } from "./api";

type Pending =
  | { kind: "create" } | { kind: "role"; staff: Staff; role: Role } | { kind: "disable"; staff: Staff } | { kind: "reset"; staff: Staff };

/**
 * The staff themselves: SUPERADMIN only (docs/reference/admin.md). Minting a login and resetting
 * an authenticator take a fresh code -- they are how someone else becomes able to act here.
 * Nothing on this page acts on your own row; admin-bff refuses it too.
 */
export function Admins() {
  const me = useAdmin();
  const [staff, setStaff] = useState<Staff[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState<Pending | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(() => {
    fetchAdmins().then(setStaff).catch((e: unknown) => {
      if (e instanceof AdminApiError && !e.notAuthenticated) setError(e.message);
    });
  }, []);
  useEffect(() => { load(); }, [load]);

  if (error) return <p role="alert" className="text-danger">{error}</p>;
  if (!staff || !me) return <p className="text-dim" aria-busy>Loading…</p>;
  const done = (message: string) => { setPending(null); setNotice(message); load(); };

  return (
    <>
      {notice && <p role="status" className="m-0 rounded-md border border-[var(--success-border)] bg-success-bg px-4 py-2.5 text-success">{notice}</p>}

      <Table
        empty="No admins."
        head={["Admin", "Role", "Authenticator", "State", "Created", ""]}
        footer={<>
          <span>{staff.length} admin{staff.length === 1 ? "" : "s"}</span>
          <button className="btn btn-sm btn-primary" onClick={() => setPending({ kind: "create" })}>Add admin</button>
        </>}
        rows={staff.map((s) => {
          const self = s.id === me.id;
          const disabled = !!s.disabledAt;
          return {
            key: String(s.id),
            cells: [
              <span key="e" className={disabled ? "text-muted" : "font-semibold"}>{s.email}{self && <span className="pill pill-off ml-2">you</span>}</span>,
              self || disabled ? <span key="r">{s.role}</span> : (
                <select key="r" aria-label={`Role of ${s.email}`} value={s.role} className="input"
                        onChange={(e) => setPending({ kind: "role", staff: s, role: e.target.value as Role })}>
                  <option>VIEWER</option><option>OPERATOR</option><option>SUPERADMIN</option>
                </select>
              ),
              <span key="t" className={`pill ${s.totpEnrolled ? "pill-ok" : "pill-warn"}`}>{s.totpEnrolled ? "Enrolled" : "Not yet"}</span>,
              <span key="s" className={`pill ${disabled ? "pill-bad" : "pill-ok"}`}>{disabled ? "Disabled" : "Active"}</span>,
              <span key="c" className="font-mono text-muted">{s.createdAt.slice(0, 10)}</span>,
              self || disabled ? <span key="a" /> : (
                <span key="a" className="flex justify-end gap-2">
                  {s.totpEnrolled && <button className="btn btn-sm" onClick={() => setPending({ kind: "reset", staff: s })}>Reset authenticator</button>}
                  <button className="btn btn-sm btn-danger" onClick={() => setPending({ kind: "disable", staff: s })}>Disable</button>
                </span>
              ),
            ],
          };
        })}
      />

      {pending?.kind === "create" && <CreateDialog onClose={() => setPending(null)} onDone={done} />}
      {pending?.kind === "role" && (
        <Confirm title={`Change ${pending.staff.email} to ${pending.role}`} confirmLabel="Change role" code
                 body="A role is a floor: everything the role below may do, and more. It takes effect on their next request."
                 onConfirm={async (_r, code) => { await changeRole(pending.staff.id, pending.role, code); done(`${pending.staff.email} is now ${pending.role}.`); }}
                 onClose={() => { setPending(null); load(); }} />
      )}
      {pending?.kind === "reset" && (
        <Confirm title={`Reset the authenticator of ${pending.staff.email}`} confirmLabel="Reset" code tone="danger"
                 body="Their seed is discarded and every session ends. At their next login they enrol a new authenticator. This is the only recovery there is; there are no recovery codes by design."
                 onConfirm={async (_r, code) => { await resetTotp(pending.staff.id, code); done(`${pending.staff.email} will enrol again at next login.`); }}
                 onClose={() => setPending(null)} />
      )}
      {pending?.kind === "disable" && (
        <Confirm title={`Disable ${pending.staff.email}`} confirmLabel="Disable" tone="danger"
                 body="They are signed out everywhere now and cannot sign in again. There is no enable: a new row is made instead, so the audit trail of the old one is never reused."
                 onConfirm={async () => { await disableAdmin(pending.staff.id); done(`${pending.staff.email} disabled.`); }}
                 onClose={() => setPending(null)} />
      )}
    </>
  );
}

/** Email, role, a first password they will change, and the code -- last, as always. */
function CreateDialog({ onClose, onDone }: { onClose: () => void; onDone: (m: string) => void }) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [role, setRole] = useState<Role>("VIEWER");
  const [code, setCode] = useState("");
  const { busy, error, run } = useBusy();
  const ready = email.includes("@") && password.length >= 12 && code.length === 6;

  return (
    <Dialog title="Add admin" onClose={onClose}
            onSubmit={() => ready && !busy && run(async () => {
              await createAdmin(email.trim(), password, role, code);
              onDone(`${email.trim()} added as ${role}. They enrol an authenticator at first login.`);
            })}
            footer={<>
              <button type="button" className="btn" onClick={onClose}>Cancel</button>
              <button type="submit" className="btn btn-primary" disabled={!ready || busy}>Add</button>
            </>}>
      <Field label="Email"><input type="email" value={email} onChange={(e) => setEmail(e.target.value)} className="input" autoFocus /></Field>
      <Field label="First password — at least 12 characters, theirs to change">
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} className="input" autoComplete="new-password" />
      </Field>
      <Field label="Role">
        <select value={role} onChange={(e) => setRole(e.target.value as Role)} className="input">
          <option value="VIEWER">VIEWER — reads everything</option>
          <option value="OPERATOR">OPERATOR — the day&apos;s work</option>
          <option value="SUPERADMIN">SUPERADMIN — also manages admins</option>
        </select>
      </Field>
      <CodeInput value={code} onChange={setCode} />
      {error && <p role="alert" className="m-0 text-danger">{error}</p>}
    </Dialog>
  );
}
