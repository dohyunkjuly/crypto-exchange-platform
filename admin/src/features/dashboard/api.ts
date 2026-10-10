import { api } from "@/lib/api";

/** A figure today so far, and the same span of yesterday. Decimal strings, as money always is. */
export type Compare = { today: string; yesterday: string };
export type Pair = { symbol: string; quote: string; volumeToday: string; volumeYesterday: string; tradesToday: number };
export type Dashboard = {
  asOf: string; signups: Compare; trades: Compare; volume: Compare; fees: Compare; pairs: Pair[];
};

export const fetchDashboard = () => api<Dashboard>("/admin/dashboard");
