import { api } from "@/lib/api";

/** A fee override in bps; {@code effective} is false once it has expired. */
export type FeeGrant = {
  makerFeeBps: number; takerFeeBps: number; reason: string | null; expiresAt: string | null; effective: boolean;
  grantedBy: string | null; updatedAt: string;
};
export type LimitGrant = {
  maxOrders: number | null; maxStops: number | null; reason: string | null; expiresAt: string | null; effective: boolean;
  grantedBy: string | null; updatedAt: string;
};
/** What the customer pays now and why. A sub-account pays its master's fee, so for a sub it is all the master's. */
export type FeeView = {
  makerFeeBps: number; takerFeeBps: number; source: "OVERRIDE" | "TIER";
  tierLevel: number | null; tierName: string | null; subAccount: boolean; override: FeeGrant | null;
};
/** A cap and the default it replaces; `symbol` is null for the account-wide one. */
export type LimitView = { symbol: string | null; defaultOrders: number; defaultStops: number; own: LimitGrant | null };
export type Overrides = { fee: FeeView; orderLimit: LimitView; marketLimits: LimitView[] };

export type FeeBody = { makerFeeBps: number; takerFeeBps: number; reason: string; expiresAt: string | null };
export type LimitBody = { symbol?: string; maxOrders: number | null; maxStops: number | null; reason: string; expiresAt: string | null };

const base = (id: number) => `/admin/users/${id}/overrides`;
export const fetchOverrides = (id: number) => api<Overrides>(base(id));
export const setFee = (id: number, body: FeeBody, totp: string) => api<Overrides>(`${base(id)}/fee`, { method: "POST", body, totp });
export const removeFee = (id: number, totp: string) => api<Overrides>(`${base(id)}/fee`, { method: "DELETE", totp });
export const setOrderLimit = (id: number, body: LimitBody) => api<Overrides>(`${base(id)}/order-limit`, { method: "POST", body });
export const removeOrderLimit = (id: number) => api<Overrides>(`${base(id)}/order-limit`, { method: "DELETE" });
export const setMarketLimit = (id: number, body: LimitBody) => api<Overrides>(`${base(id)}/markets`, { method: "POST", body });
export const removeMarketLimit = (id: number, symbol: string) =>
  api<Overrides>(`${base(id)}/markets/${encodeURIComponent(symbol)}`, { method: "DELETE" });
