import { api } from "@/lib/bff/client";

export type Balance = { asset: string; available: string; locked: string; total: string; version: number };
export type Asset = { symbol: string; name: string; assetType: string; displayDecimals: number };
export type KycStatus = { status: string; level: string; submittedAt: string | null; reviewedAt: string | null; expiresAt: string | null; rejectionReason: string | null };
export type MfaStatus = { enabled: boolean; recoveryCodesRemaining: number; enabledAt: string | null };
export type ApiKey = {
  /** A ULID string, never a counted number. */
  id: string; apiKey: string; label: string; canRead: boolean; canTrade: boolean; canWithdraw: boolean;
  ipAllowlist: string[]; lastUsedAt: string | null; expiresAt: string | null; createdAt: string;
  revokedAt: string | null; revokedReason: string | null;
};
export type FeeRates = { makerBps: number; takerBps: number; makerPercent: string; takerPercent: string; tierLevel: number | null; tierName: string | null; custom: boolean };

export const fetchBalances = (signal?: AbortSignal, account?: "lead") => api<Balance[]>("/bff/wallet/balances", { signal, account });
export const balancesKey = (account?: "lead") => (account ? ["balances", account] : ["balances"]);
export const fetchAssets = (signal?: AbortSignal) => api<Asset[]>("/bff/wallet/assets", { signal });
export const fetchKyc = (signal?: AbortSignal) => api<KycStatus>("/bff/kyc", { signal });
export const fetchMfa = (signal?: AbortSignal) => api<MfaStatus>("/bff/mfa", { signal });
export const fetchApiKeys = (signal?: AbortSignal) => api<ApiKey[]>("/bff/api-keys", { signal });
export const fetchFees = (signal?: AbortSignal) => api<FeeRates>("/bff/fees", { signal });


export type Order = {
  orderId: string; clientOrderId: string | null; symbol: string; side: "BUY" | "SELL"; orderType: string;
  timeInForce: string | null; status: string; price: string | null; stopPrice: string | null;
  /** Set on a trailing order, whose stopPrice is then its activation price, not the live stop. */
  trailingDeltaBps: number | null; triggerDirection: "RISE" | "FALL" | null;
  quantity: string | null; quoteOrderQty: string | null; filledQuantity: string; remainingQuantity: string | null;
  cumulativeQuoteQuantity: string; averageFillPrice: string | null; rejectReason: string | null;
  createdAt: string; updatedAt: string;
  /** An amend is on its way to the engine; a second one is refused (5026) until it lands. */
  amendPending?: boolean;
  /** Never from the server: the reason the socket gave for refusing this row's last amend. */
  amendRejectReason?: string | null;
};
export type MyTrade = {
  tradeId: string; symbol: string; orderId: string; orderType: string | null; price: string; quantity: string; quoteQuantity: string;
  side: "BUY" | "SELL"; isMaker: boolean; fee: string; feeAsset: string; time: string;
};

/**
 * Whose orders and fills. Undefined is the signed-in account's own. A number is one of its
 * sub-accounts, read by its master through /bff/sub-accounts/{id}/... -- the same shapes.
 * "lead" is its lead account, traded from this session: the same endpoints as its own, named
 * on the request.
 */
export type Account = number | "lead";
const named = (a?: Account) => (a === "lead" ? a : undefined);
const ordersBase = (a?: Account) => (typeof a === "number" ? `/bff/sub-accounts/${a}/orders` : "/bff/orders");
const tradesBase = (a?: Account) => (typeof a === "number" ? `/bff/sub-accounts/${a}/trades` : "/bff/trades");
/** ["orders", "open"] is what the socket keeps fresh; another account's list is its own entry.
 *  `null` is "no account to read" (Orders.tsx). */
export const openOrdersKey = (a?: Account | null) => (a === undefined ? ["orders", "open"] : ["orders", "open", "sub", a ?? "none"]);
export const fetchOrders = (signal?: AbortSignal, sub?: Account) => api<Order[]>(ordersBase(sub), { signal, account: named(sub) });
/** from/to are ISO instants; the server refuses a window over 90 days. */
export type HistoryFilter = {
  from: string; to: string; symbol?: string; side?: "BUY" | "SELL"; timeField?: "CREATED_AT" | "UPDATED_AT";
};
/** A page of either history; a short page means the end, since the server gives no count. */
export type Page = { page: number; size: number };
function historyParams({ page, size }: Page, f: HistoryFilter): string {
  const p = new URLSearchParams({ page: String(page), size: String(size), from: f.from, to: f.to });
  if (f.symbol) p.set("symbol", f.symbol);
  if (f.side) p.set("side", f.side);
  if (f.timeField) p.set("timeField", f.timeField);
  return p.toString();
}
export const fetchOrderHistory = (page: Page, filter: HistoryFilter, signal?: AbortSignal, sub?: Account) =>
  api<Order[]>(`${ordersBase(sub)}/history?${historyParams(page, filter)}`, { signal, account: named(sub) });
export const fetchMyTrades = (page: Page, filter: HistoryFilter, signal?: AbortSignal, sub?: Account) =>
  api<MyTrade[]>(`${tradesBase(sub)}?${historyParams(page, filter)}`, { signal, account: named(sub) });
/** 202: the engine has the request, not the result. The row updates when the event lands. */
export const cancelOrder = ({ orderId, sub }: { orderId: string; sub?: Account }) =>
  api<{ orderId: string }>(`${ordersBase(sub)}/${orderId}`, { method: "DELETE", account: named(sub) });

/** 202: queued, not applied. Never retried: the row changes when the socket reports the outcome. */
export const amendOrder = ({ orderId, account, ...body }: { orderId: string; account?: "lead"; price?: string; stopPrice?: string; quantity?: string; quoteOrderQty?: string }) =>
  api<{ orderId: string; status: string }>(`/bff/orders/${orderId}`, { method: "PATCH", body, account });

export type KycSubmission = {
  legalFirstName: string; legalLastName: string; dateOfBirth: string;
  nationality: string; residenceCountry: string;
};
/** 202 with the new status. Grants nothing itself; a reviewer does. */
export const submitKyc = (body: KycSubmission) => api<KycStatus>("/bff/kyc", { method: "POST", body });

export type KycDetails = {
  legalFirstName?: string; legalLastName?: string; dateOfBirth?: string;
  nationality?: string; residenceCountry?: string;
};
/** Its own endpoint so the status poll never carries personal data; fetched only where shown. */
export const fetchKycDetails = (signal?: AbortSignal) => api<KycDetails>("/bff/kyc/details", { signal });
/** Which open orders "Cancel all" means: a tab, narrowed by its filters. Unset means any. */
export type CancelAllFilter = { scope: "ALL" | "BOOK" | "TRIGGERS"; symbol?: string; side?: "BUY" | "SELL"; type?: string };
/** 202 with how many matched. The server picks the orders from the filter, never from ids we send. */
export function cancelAllOrders(f: CancelAllFilter, sub?: Account) {
  const p = new URLSearchParams({ scope: f.scope });
  if (f.symbol) p.set("symbol", f.symbol);
  if (f.side) p.set("side", f.side);
  if (f.type) p.set("type", f.type);
  return api<{ count: number }>(`${ordersBase(sub)}?${p}`, { method: "DELETE", account: named(sub) });
}

/** Step one of turning on 2FA: a secret to scan. Nothing is active until it is confirmed. */
export type TotpEnrolment = { secret: string; provisioningUri: string };
export const startTotp = () => api<TotpEnrolment>("/bff/mfa/totp", { method: "POST" });
/** Confirming, and regenerating, both answer with the recovery codes -- the only time they exist in plaintext. */
export const confirmTotp = (code: string) => api<{ recoveryCodes: string[] }>("/bff/mfa/totp/confirm", { method: "POST", body: { code } });
export const regenerateRecoveryCodes = (code: string) => api<{ recoveryCodes: string[] }>("/bff/mfa/recovery-codes", { method: "POST", body: { code } });
export const disableMfa = (code: string) => api<void>("/bff/mfa/disable", { method: "POST", body: { code } });

export type LinkedIdentity = { provider: string; email: string; linkedAt: string; lastLoginAt: string | null };
export const fetchIdentities = (signal?: AbortSignal) => api<LinkedIdentity[]>("/bff/auth/identities", { signal });
export const unlinkGoogle = (password: string) => api<void>("/bff/auth/google/unlink", { method: "POST", body: { password } });

export type NewApiKey = {
  label: string; canRead: boolean; canTrade: boolean; canWithdraw: boolean;
  ipAllowlist: string[]; expiresAt: string | null; mfaCode: string;
};
/** Carries the secret, which the server never returns again. */
export type CreatedApiKey = { id: string; apiKey: string; secret: string; label: string; canRead: boolean; canTrade: boolean; canWithdraw: boolean };
export const createApiKey = (key: NewApiKey) => api<CreatedApiKey>("/bff/api-keys", { method: "POST", body: key });
export type ApiKeyEdit = {
  label: string; canTrade: boolean; canWithdraw: boolean; ipAllowlist: string[]; expiresAt: string | null; mfaCode: string;
};
/** Behind the same code as creation: widening a key is minting one in all but name. The key itself is unchanged. */
export const updateApiKey = ({ id, ...edit }: ApiKeyEdit & { id: string }) =>
  api<ApiKey>(`/bff/api-keys/${id}`, { method: "PATCH", body: edit });
/** Needs no 2FA code on purpose: a leaked key must die without waiting for a phone (ApiKeyController). */
export const revokeApiKey = (id: string) => api<void>(`/bff/api-keys/${id}`, { method: "DELETE" });

/** One proof, and the server knows which: the authenticator code when 2FA is on, the current
 *  password when not. No emailed code -- that is the logged-out reset. */
export const changePassword = (body: { newPassword: string; currentPassword?: string; mfaCode?: string }) =>
  api<void>("/bff/auth/password", { method: "POST", body });

// ------------------------------------------------------------------ sub-accounts

export type SubAccount = { id: number; email: string; label: string; frozen: boolean; createdAt: string };
export type Transfer = { transferId: string; fromUserId: number; toUserId: number; asset: string; amount: string; createdAt: string };
export type NewTransfer = { fromUserId: number; toUserId: number; asset: string; amount: string; transferId: string };
/** No canWithdraw: a sub-account's key never withdraws. The code is the master's. */
export type NewSubAccountApiKey = { label: string; canRead: boolean; canTrade: boolean; ipAllowlist: string[]; expiresAt: string | null; mfaCode: string };

export const fetchSubAccounts = (signal?: AbortSignal) => api<SubAccount[]>("/bff/sub-accounts", { signal });
export const createSubAccount = (body: { label: string }) => api<SubAccount>("/bff/sub-accounts", { method: "POST", body });
export const fetchSubAccountBalances = (id: number, signal?: AbortSignal) => api<Balance[]>(`/bff/sub-accounts/${id}/balances`, { signal });
/** Every sub's balances, keyed by sub id: the asset screen lists them all, so it asks once. */
export const fetchAllSubAccountBalances = (signal?: AbortSignal) => api<Record<string, Balance[]>>("/bff/sub-accounts/balances", { signal });
export const setSubAccountFrozen = ({ id, frozen }: { id: number; frozen: boolean }) =>
  api<SubAccount>(`/bff/sub-accounts/${id}/frozen`, { method: "PATCH", body: { frozen } });
/** `transferId` is ours, so a retry after a timeout is the same transfer -- still never retried automatically. */
export const transfer = (body: NewTransfer) => api<Transfer>("/bff/sub-accounts/transfers", { method: "POST", body });
export const fetchTransfers = (signal?: AbortSignal) => api<Transfer[]>("/bff/sub-accounts/transfers", { signal });
export const fetchSubAccountApiKeys = (id: number, signal?: AbortSignal) => api<ApiKey[]>(`/bff/sub-accounts/${id}/api-keys`, { signal });
/** Every sub's keys, keyed by sub id: the key screen lists them all, so it asks once. */
export const fetchAllSubAccountApiKeys = (signal?: AbortSignal) => api<Record<string, ApiKey[]>>("/bff/sub-accounts/api-keys", { signal });
export const createSubAccountApiKey = ({ id, ...key }: NewSubAccountApiKey & { id: number }) =>
  api<CreatedApiKey>(`/bff/sub-accounts/${id}/api-keys`, { method: "POST", body: key });
export const revokeSubAccountApiKey = ({ id, keyId }: { id: number; keyId: string }) =>
  api<void>(`/bff/sub-accounts/${id}/api-keys/${keyId}`, { method: "DELETE" });
