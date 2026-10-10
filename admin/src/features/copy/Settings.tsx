"use client";

import { useState } from "react";
import { CodeInput, Dialog, Field, useBusy } from "@/components/Dialog";
import { Table } from "@/components/Table";
import { bps } from "@/features/fees/Fees";
import { exact } from "@/lib/money";
import { useLoad } from "@/lib/search";
import { useAdmin } from "@/lib/session";
import { fetchCopySettings, saveCopySettings, type CopySettings, type CopySettingsBody } from "./api";

const load = () => fetchCopySettings();

type Key = keyof CopySettingsBody;
/** Every setting, in the order the page and the form show them. `kind` is how it is typed and read. */
const FIELDS: { key: Key; label: string; kind: "bps" | "count" | "amount" }[] = [
  { key: "profitShareBps", label: "Leader's share of a follower's profit, for anyone who follows from now on", kind: "bps" },
  { key: "failureLimit", label: "Failed copies in a row that close a copy account", kind: "count" },
  { key: "maxFollowersPerLeader", label: "Followers per leader", kind: "count" },
  { key: "maxCopiesPerUser", label: "Copy accounts per customer", kind: "count" },
  { key: "minFixedRatioAmount", label: "Minimum to follow, fixed ratio (USDT)", kind: "amount" },
  { key: "minFixedAmount", label: "Minimum to follow, fixed amount (USDT)", kind: "amount" },
  { key: "leadMinBalance", label: "What a lead account keeps (USDT)", kind: "amount" },
  { key: "leadMaxBalance", label: "The most a lead account may hold (USDT)", kind: "amount" },
  { key: "closeSellThreshold", label: "On closing, a holding worth less is returned unsold (USDT)", kind: "amount" },
];

/**
 * What every copy is sized and judged by. How far a copied buy may pay is each follower's own
 * choice, not a setting here. A change applies from the next copy, and takes a fresh code.
 */
export function Settings() {
  const admin = useAdmin();
  const { data: loaded, error } = useLoad(load, "");
  // A write answers with the row as it now is, so that is what is shown from then on.
  const [saved, setSaved] = useState<CopySettings | null>(null);
  const [editing, setEditing] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  if (error) return <p role="alert" className="text-danger">{error}</p>;
  const data = saved ?? loaded;
  const canAct = admin?.role !== undefined && admin.role !== "VIEWER";
  const done = (message: string) => (now: CopySettings) => {
    setSaved(now); setEditing(false); setNotice(message);
  };

  return (
    <>
      {notice && <p role="status" className="m-0 rounded-md border border-[var(--success-border)] bg-success-bg px-4 py-2.5 text-success">{notice}</p>}
      {canAct && data && (
        <div className="flex justify-end">
          <button className="btn btn-primary" onClick={() => setEditing(true)}>Edit settings</button>
        </div>
      )}
      <Table
        empty="Loading…"
        head={["Setting", "Value"]}
        right={[1]}
        rows={data ? FIELDS.map((f) => ({
          key: f.key,
          cells: [
            <span key="l">{f.label}</span>,
            f.kind === "bps" ? <span key="v">{bps(data[f.key] as number)}</span>
              : <span key="v" className="font-mono">{f.kind === "amount" ? exact(data[f.key] as string) : String(data[f.key])}</span>,
          ],
        })) : []}
      />

      {editing && data && <SettingsDialog settings={data} onClose={() => setEditing(false)} onDone={done("Settings saved. They apply from the next copy.")} />}
    </>
  );
}

const whole = (v: string) => /^\d+$/.test(v.trim());
const decimal = (v: string) => /^\d+(\.\d{1,8})?$/.test(v.trim());

/** The whole row in one form, then the code: the page saves every setting together. */
function SettingsDialog({ settings, onClose, onDone }: { settings: CopySettings; onClose: () => void; onDone: (now: CopySettings) => void }) {
  const [form, setForm] = useState(() => Object.fromEntries(FIELDS.map((f) => [f.key, String(settings[f.key])])) as Record<Key, string>);
  const [code, setCode] = useState("");
  const { busy, error, run } = useBusy();
  const ready = code.length === 6 && FIELDS.every((f) => (f.kind === "amount" ? decimal(form[f.key]) : whole(form[f.key])));

  return (
    <Dialog title="Edit copy settings" onClose={onClose}
            onSubmit={() => ready && !busy && run(async () => {
              const body = Object.fromEntries(FIELDS.map((f) => [f.key,
                f.kind === "amount" ? form[f.key].trim() : Number(form[f.key])]));
              onDone(await saveCopySettings(body as CopySettingsBody, code));
            })}
            footer={<>
              <button type="button" className="btn" onClick={onClose}>Cancel</button>
              <button type="submit" className="btn btn-primary" disabled={!ready || busy}>Save</button>
            </>}>
      {FIELDS.map((f) => (
        <Field key={f.key} label={f.kind === "bps" ? `${f.label} (bps)` : f.label}>
          <input aria-label={f.label} value={form[f.key]} onChange={(e) => setForm({ ...form, [f.key]: e.target.value })} className="input font-mono" />
        </Field>
      ))}
      <CodeInput value={code} onChange={setCode} />
      {error && <p role="alert" className="m-0 text-danger">{error}</p>}
    </Dialog>
  );
}
