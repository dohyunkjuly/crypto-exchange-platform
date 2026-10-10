import { api } from "@/lib/api";

export type Status = "ACTIVE" | "SUSPENDED" | "CLOSED";

export type Summary = {
  id: number; email: string; name: string | null; type: "PRIMARY" | "SUB"; status: Status;
  frozenReason: string | null; frozenWithMaster: boolean;
  /** This row's own suspension; a sub's master's is on the master. */
  tradingSuspendedAt: string | null; tradingSuspendedReason: string | null;
  kycLevel: string; kycVerifiedUntil: string | null; mfaEnabled: boolean; emailVerified: boolean; createdAt: string;
};

export type Page = { items: Summary[]; total: number; page: number; size: number };

export type BalanceLine = { asset: string; displayDecimals: number; available: string; locked: string; total: string };
export type KeyLine = {
  id: string; userId: number; apiKey: string; label: string; canRead: boolean; canTrade: boolean;
  lastUsedAt: string | null; expiresAt: string | null; createdAt: string; revokedAt: string | null;
};
export type SessionLine = {
  id: number; ipAddress: string | null; userAgent: string | null; countryCode: string | null;
  createdAt: string; lastSeenAt: string | null; expiresAt: string;
};

/** Sub-accounts live here and only here: a sub is found through its master, the way it is owned. */
export type Detail = {
  user: Summary; master: Summary | null; subAccounts: Summary[];
  balances: BalanceLine[]; apiKeys: KeyLine[]; sessions: SessionLine[];
};

export type Changed = { user: Summary; subsAffected: number[]; sessionsRevoked: number };

export function fetchUsers(q: string, status: Status | "", page: number, size = 25) {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (q) params.set("q", q);
  if (status) params.set("status", status);
  return api<Page>(`/admin/users?${params}`);
}
export const fetchUser = (id: number) => api<Detail>(`/admin/users/${id}`);
export const freezeUser = (id: number, reason: string) =>
  api<Changed>(`/admin/users/${id}/freeze`, { method: "POST", body: { reason } });
export const suspendTrading = (id: number, reason: string) =>
  api<Summary>(`/admin/users/${id}/suspend-trading`, { method: "POST", body: { reason } });
export const resumeTrading = (id: number) => api<Summary>(`/admin/users/${id}/resume-trading`, { method: "POST", body: {} });
export const unfreezeUser = (id: number) => api<Changed>(`/admin/users/${id}/unfreeze`, { method: "POST", body: {} });
export const revokeSessions = (id: number) =>
  api<{ sessionsRevoked: number }>(`/admin/users/${id}/sessions/revoke`, { method: "POST", body: {} });
export const revokeApiKey = (id: number, keyId: string, ownerId: number) =>
  api<{ revoked: boolean }>(`/admin/users/${id}/api-keys/${keyId}?ownerId=${ownerId}`, { method: "DELETE" });
