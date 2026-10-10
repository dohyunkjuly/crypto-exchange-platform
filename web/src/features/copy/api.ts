import { api } from "@/lib/bff/client";
import type { ApiKey, CreatedApiKey, NewSubAccountApiKey } from "@/features/my/shared/api";

/** Copy trading (docs/reference/copy-trading.md). Every amount is a decimal string. */
/** A leader as anyone may see one: a display name, never an address or a customer id. */
/** `profitShareBps`: what following this leader now costs -- their own rate where staff set one, else the exchange's. */
export type Leader = { id: string; displayName: string; bio: string | null; followers: number; accepting: boolean; since: string; portfolioShown: boolean; profitShareBps: number; stats: LeaderStats | null };
/** What staff set, as a follower is told it. Amounts in the copy asset. */
export type Terms = {
  quoteAsset: string; minFixedRatioAmount: string; minFixedAmount: string; profitShareBps: number; maxCopies: number;
  maxFollowers: number; failureLimit: number; leadMinBalance: string;
};
/**
 * A leader's figures as of the last nightly run; null before the first. Returns and the success
 * rate are percentages ("12.34", "-3.5"), each null until there is something to measure.
 */
export type LeaderStats = {
  days7: Window; days30: Window; days90: Window; copySuccessRate: string | null; fundsCopying: string;
  /** The last thirty days' cumulative return, one point a snapshot, oldest first, starting at "0". */
  series: { day: string; value: string }[]; asOf: string;
};
export type CopyMode = "FIXED_RATIO" | "FIXED_AMOUNT";
export type FollowStatus = "ACTIVE" | "CLOSING" | "CLOSED";
export type CloseReason = "STOPPED" | "FAILURES" | "LEADER_CLOSED" | "LEADER_SUSPENDED";
export type Holding = { asset: string; available: string; locked: string };
export type Follow = {
  id: string; leaderId: string; leaderName: string; mode: CopyMode; amountPerOrder: string | null;
  /** How far above the leader's price this follower's copied buys may fill, in basis points. */
  slippageCapBps: number;
  status: FollowStatus; closeReason: CloseReason | null; failuresInRow: number;
  /** The rate this account agreed to, frozen when it started; realized is signed and net of fees, shared is what the leader has been paid of it. */
  profitShareBps: number; realizedProfit: string; sharedProfit: string;
  /** What the account holds, in the copy asset, each coin at its market's last trade. */
  value: string;
  createdAt: string; closedAt: string | null; holdings: Holding[];
};
export type CopyResult = "PLACED" | "FILLED" | "FAILED" | "SKIPPED";
export type CopyReason = "NOTHING_TO_SELL" | "INSUFFICIENT_BALANCE" | "BELOW_MINIMUM" | "MARKET_CLOSED" | "ORDER_LIMIT"
  | "NOT_ELIGIBLE" | "NOT_FILLED" | "REJECTED" | "REFUSED" | "MARKET_PAUSED";
/** One copy of a lead order, or one sale made while closing. `orderId` is null when nothing was placed. */
export type CopyLine = {
  id: string; symbol: string; side: "BUY" | "SELL"; status: CopyResult; reason: CopyReason | null;
  orderId: string | null; closing: boolean; createdAt: string;
  /** What its order traded: base filled, average price, quote total. Null until something has. */
  filled: string | null; averagePrice: string | null; total: string | null;
};

export const fetchLeaders = (signal?: AbortSignal) => api<{ leaders: Leader[]; terms: Terms }>("/bff/copy/leaders", { signal });
/** `portfolio` is null when the leader keeps it private; `leadingBalance` is the lead account as the last snapshot valued it. */
export type LeaderProfile = { leader: Leader; terms: Terms; leadingBalance: string | null; portfolio: Portfolio | null };
/** What the lead account holds; its trades are paged on their own (`fetchLeaderTrades`). */
export type Portfolio = { holdings: { asset: string; amount: string; value: string | null }[] };
export type LeadTrade = { time: string; symbol: string; side: "BUY" | "SELL"; price: string; quantity: string; quoteQuantity: string };
export const fetchLeader = (id: string, signal?: AbortSignal) => api<LeaderProfile>(`/bff/copy/leaders/${id}`, { signal });
export const fetchFollows = (signal?: AbortSignal) => api<Follow[]>("/bff/copy/follows", { signal });
/** One settlement of the profit share: what was due then and what the account could pay. */
export type ShareLine = { id: string; trigger: "WEEKLY" | "CLOSE" | "WITHDRAWAL"; due: string; paid: string; createdAt: string };
export const fetchFollow = (id: string, signal?: AbortSignal) =>
  api<{ follow: Follow }>(`/bff/copy/follows/${id}`, { signal });
/** Every history here comes a page at a time, newest first; a full page means there may be another. */
export const PER_PAGE = 20;
export const fetchCopies = (id: string, page: number, signal?: AbortSignal) =>
  api<CopyLine[]>(`/bff/copy/follows/${id}/copies?page=${page}`, { signal });
export const fetchShares = (id: string, page: number, signal?: AbortSignal) =>
  api<ShareLine[]>(`/bff/copy/follows/${id}/shares?page=${page}`, { signal });
/** A leader's fills; empty while the leader keeps the portfolio private. */
export const fetchLeaderTrades = (id: string, page: number, signal?: AbortSignal) =>
  api<LeadTrade[]>(`/bff/copy/leaders/${id}/trades?page=${page}`, { signal });

/** `followId` is made by the caller once per form: a request sent twice is one follow. */
export const follow = (body: { followId: string; leaderId: string; mode: CopyMode; amount: string; amountPerOrder?: string; slippageCapBps: number }) =>
  api<Follow>("/bff/copy/follows", { method: "POST", body });
/** `transferId` is made by the caller once per form, for the same reason. */
export const addFunds = ({ id, ...body }: { id: string; transferId: string; amount: string }) =>
  api<void>(`/bff/copy/follows/${id}/deposits`, { method: "POST", body });
export const removeFunds = ({ id, ...body }: { id: string; transferId: string; amount: string }) =>
  api<void>(`/bff/copy/follows/${id}/withdrawals`, { method: "POST", body });
export const stopCopying = (id: string) => api<void>(`/bff/copy/follows/${id}/stop`, { method: "POST", body: {} });

export type LeadState = "ACTIVE" | "UNAVAILABLE" | "CLOSED";
/** The customer's own leadership. A status staff set is never named, and neither is its reason. */
/** One window's figures: the return, what was earned in the copy asset, the deepest fall from a high. */
export type Window = { return: string | null; pnl: string | null; maxDrawdown: string | null };
/** `hasAccount`: a lead account exists, whatever became of the leading; it is theirs to trade and to empty. */
export type MyLead = { id: string; displayName: string; bio: string | null; status: LeadState; followers: number; hasAccount: boolean; portfolioShown: boolean; earned: string; holdings: Holding[] };
/** The leader's switch: show the lead account's holdings and trades on the profile, or keep them private. */
export const setPortfolioShown = (shown: boolean) => api<MyLead>("/bff/copy/lead/portfolio", { method: "PATCH", body: { shown } });
/** `lead` is null for a customer who has never applied. */
export type LeadPage = { lead: MyLead | null; quoteAsset: string; minBalance: string; maxBalance: string };

export const fetchLead = (signal?: AbortSignal) => api<LeadPage>("/bff/copy/lead", { signal });
/** One step: `transferId` is ours, so a retry funds once. */
export const applyToLead = (body: { displayName: string; bio?: string; showPortfolio: boolean; transferId: string; amount: string }) =>
  api<MyLead>("/bff/copy/lead/apply", { method: "POST", body });
export const fundLead = (body: { transferId: string; amount: string }) => api<void>("/bff/copy/lead/deposits", { method: "POST", body });
export const withdrawLead = (body: { transferId: string; asset: string; amount: string }) =>
  api<void>("/bff/copy/lead/withdrawals", { method: "POST", body });
export const stopLeading = () => api<MyLead>("/bff/copy/lead/close", { method: "POST", body: {} });
/** The lead account's keys: minted with the customer's own code, like a sub-account's, and never withdrawing. */
export const fetchLeadApiKeys = (signal?: AbortSignal) => api<ApiKey[]>("/bff/copy/lead/api-keys", { signal });
export const createLeadApiKey = (key: NewSubAccountApiKey) => api<CreatedApiKey>("/bff/copy/lead/api-keys", { method: "POST", body: key });
export const revokeLeadApiKey = (keyId: string) => api<void>(`/bff/copy/lead/api-keys/${keyId}`, { method: "DELETE" });
