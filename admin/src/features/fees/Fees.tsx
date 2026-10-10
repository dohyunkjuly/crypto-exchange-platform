"use client";

import { useState } from "react";
import { CodeInput, Dialog, Field, useBusy } from "@/components/Dialog";
import { Table } from "@/components/Table";
import { exact } from "@/lib/money";
import { useLoad } from "@/lib/search";
import { useAdmin } from "@/lib/session";
import { addLevel, changeLevel, fetchFees, type Level, type LevelBody } from "./api";

const load = () => fetchFees();

/** Basis points as staff read them: the bps, and the percentage a customer sees. */
export function bps(value: number) {
  const pct = (value / 100).toFixed(2);
  return <><span className="font-mono">{value}</span> <span className="text-muted">bps · {pct}%</span></>;
}

/**
 * The spot fee schedule, one row per level. A change applies to new orders; an order keeps the
 * rate it was accepted with.
 */
export function Fees() {
  const admin = useAdmin();
  const [reload, setReload] = useState(0);
  const { data, error } = useLoad(load, String(reload));
  const [editing, setEditing] = useState<Level | "new" | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  if (error) return <p role="alert" className="text-danger">{error}</p>;
  const canAct = admin?.role !== undefined && admin.role !== "VIEWER";
  const levels = data ?? [];
  const top = levels[levels.length - 1];

  return (
    <>
      {notice && <p role="status" className="m-0 rounded-md border border-[var(--success-border)] bg-success-bg px-4 py-2.5 text-success">{notice}</p>}
      {canAct && data && (
        <div className="flex justify-end">
          <button className="btn btn-primary" onClick={() => setEditing("new")}>Add level</button>
        </div>
      )}
      <Table
        empty={data ? "No fee levels." : "Loading…"}
        head={["Level", "Name", "30-day volume (USDT)", "Maker", "Taker", ""]}
        right={[2, 3, 4, 5]}
        rows={levels.map((l) => ({
          key: String(l.level),
          cells: [
            <span key="l" className="font-mono">{l.level}</span>,
            <span key="n" className="font-semibold">{l.name}</span>,
            <span key="v" className="font-mono">{exact(l.min30dVolume)}</span>,
            <span key="m" className={l.makerFeeBps < 0 ? "text-success" : ""}>{bps(l.makerFeeBps)}</span>,
            <span key="t">{bps(l.takerFeeBps)}</span>,
            canAct ? <button key="e" className="btn btn-sm" onClick={() => setEditing(l)}>Edit</button> : <span key="e" />,
          ],
        }))}
      />

      {editing && (
        <LevelDialog level={editing === "new" ? null : editing} nextLevel={top ? top.level + 1 : 0}
                     onClose={() => setEditing(null)}
                     onDone={(saved, added) => {
                       setEditing(null);
                       setNotice(`${saved.name} ${added ? "added" : "saved"}. It applies to new orders.`);
                       setReload((n) => n + 1);
                     }} />
      )}
    </>
  );
}

/** One level's name, volume and rates, then the code. A new level goes on top. */
function LevelDialog({ level, nextLevel, onClose, onDone }: {
  level: Level | null; nextLevel: number; onClose: () => void; onDone: (saved: Level, added: boolean) => void;
}) {
  const [form, setForm] = useState({
    name: level?.name ?? "", min30dVolume: level?.min30dVolume ?? "",
    maker: level ? String(level.makerFeeBps) : "", taker: level ? String(level.takerFeeBps) : "",
  });
  const [code, setCode] = useState("");
  const { busy, error, run } = useBusy();
  const whole = (v: string) => /^-?\d+$/.test(v.trim());
  const ready = form.name.trim() && form.min30dVolume.trim() && whole(form.maker) && whole(form.taker) && code.length === 6;
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value });
  const isBase = level?.level === 0;

  return (
    <Dialog title={level ? `Edit level ${level.level}` : `Add level ${nextLevel}`} onClose={onClose}
            onSubmit={() => ready && !busy && run(async () => {
              const body: LevelBody = { name: form.name.trim(), min30dVolume: form.min30dVolume.trim(),
                                        makerFeeBps: Number(form.maker), takerFeeBps: Number(form.taker) };
              onDone(level ? await changeLevel(level.level, body, code) : await addLevel(body, code), !level);
            })}
            footer={<>
              <button type="button" className="btn" onClick={onClose}>Cancel</button>
              <button type="submit" className="btn btn-primary" disabled={!ready || busy}>{level ? "Save" : "Add"}</button>
            </>}>
      <p className="m-0 text-muted leading-relaxed">Applies to orders placed from now on. Orders on the book keep the rate they were accepted with.</p>
      <Field label="Name"><input aria-label="Name" value={form.name} onChange={set("name")} maxLength={32} className="input" autoFocus /></Field>
      <Field label="30-day volume (USDT)">
        <input aria-label="30-day volume" value={form.min30dVolume} onChange={set("min30dVolume")} disabled={isBase} className="input font-mono" />
      </Field>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <Field label="Maker fee (bps, negative is a rebate)"><input aria-label="Maker fee" value={form.maker} onChange={set("maker")} className="input font-mono" /></Field>
        <Field label="Taker fee (bps)"><input aria-label="Taker fee" value={form.taker} onChange={set("taker")} className="input font-mono" /></Field>
      </div>
      <CodeInput value={code} onChange={setCode} />
      {error && <p role="alert" className="m-0 text-danger">{error}</p>}
    </Dialog>
  );
}
