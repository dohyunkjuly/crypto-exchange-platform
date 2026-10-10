import { api } from "@/lib/api";

export type Entry = {
  id: number; adminId: number | null; adminEmail: string | null; action: string;
  targetType: string | null; targetId: string | null; before: string | null; after: string | null;
  ipAddress: string; createdAt: string;
};
export type Page = { items: Entry[]; total: number; page: number; size: number };
export type Filter = { adminId: string; action: string; targetType: string; targetId: string; from: string; to: string };

export function fetchAudit(f: Filter, page: number, size = 50) {
  const p = new URLSearchParams({ page: String(page), size: String(size) });
  if (f.adminId) p.set("adminId", f.adminId);
  if (f.action) p.set("action", f.action);
  if (f.targetType) p.set("targetType", f.targetType);
  if (f.targetId) p.set("targetId", f.targetId);
  // Dates are UTC days: the log is stamped in UTC, and a day boundary anywhere else would split a night shift.
  if (f.from) p.set("from", `${f.from}T00:00:00Z`);
  if (f.to) p.set("to", `${f.to}T00:00:00Z`);
  return api<Page>(`/admin/audit?${p}`);
}
export const fetchActions = () => api<string[]>("/admin/audit/actions");
