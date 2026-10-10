import { api } from "@/lib/api";

export type LeaderStatus = "ACTIVE" | "SUSPENDED" | "CLOSED";

/** A leader beside the customer who is one. `leadUserId` is the lead account; `appliedAt` is when they first led. */
export type Leader = {
  id: string; userId: number; email: string; displayName: string; bio: string | null; status: LeaderStatus;
  followers: number; leadUserId: number | null; appliedAt: string; reviewedAt: string | null; reviewReason: string | null;
  /** This leader's own share of a follower's profit, in basis points; null while on the exchange's. */
  profitShareBps: number | null;
};
export type Leaders = { items: Leader[]; total: number; page: number; size: number };

/** What staff can do to a leader. Suspending carries a reason; the customer never reads it. */
export type Decision = "suspend" | "reinstate";

export const fetchLeaders = (status: LeaderStatus, page: number, size = 25) =>
  api<Leaders>(`/admin/copy/leaders?status=${status}&page=${page}&size=${size}`);
export const decideLeader = (id: string, decision: Decision, reason: string | null, totp: string) =>
  api<Leader>(`/admin/copy/leaders/${id}/${decision}`, { method: "POST", body: reason === null ? undefined : { reason }, totp });
/** A leader's own profit share, or null to put them back on the exchange's. Applies to whoever follows from now on. */
export const setLeaderProfitShare = (id: string, profitShareBps: number | null, totp: string) =>
  api<Leader>(`/admin/copy/leaders/${id}/profit-share`, { method: "PATCH", body: { profitShareBps }, totp });

/** What staff set for copy trading. Rates are whole basis points; amounts are decimal strings in the copy asset. */
export type CopySettings = {
  profitShareBps: number; failureLimit: number; maxFollowersPerLeader: number; maxCopiesPerUser: number;
  minFixedRatioAmount: string; minFixedAmount: string; leadMinBalance: string; leadMaxBalance: string;
  closeSellThreshold: string;
  updatedAt: string;
};
export type CopySettingsBody = Omit<CopySettings, "updatedAt">;

export const fetchCopySettings = () => api<CopySettings>("/admin/copy/settings");
/** The whole row, as the form holds it. */
export const saveCopySettings = (body: CopySettingsBody, totp: string) =>
  api<CopySettings>("/admin/copy/settings", { method: "PATCH", body, totp });
