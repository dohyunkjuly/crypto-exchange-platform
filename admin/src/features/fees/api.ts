import { api } from "@/lib/api";

/** Volume is a decimal string in the quote asset; rates are whole basis points (1 bps = 0.01%). */
export type Level = { level: number; name: string; min30dVolume: string; makerFeeBps: number; takerFeeBps: number; updatedAt: string };
export type LevelBody = Omit<Level, "level" | "updatedAt">;

export const fetchFees = () => api<Level[]>("/admin/fees");
export const changeLevel = (level: number, body: LevelBody, totp: string) =>
  api<Level>(`/admin/fees/${level}`, { method: "PATCH", body, totp });
export const addLevel = (body: LevelBody, totp: string) => api<Level>("/admin/fees", { method: "POST", body, totp });
