import { api } from "@/lib/api";
import type { Admin } from "@/lib/session";

export type Role = Admin["role"];
export type Staff = { id: number; email: string; role: Role; totpEnrolled: boolean; disabledAt: string | null; createdAt: string };

export const fetchAdmins = () => api<Staff[]>("/admin/admins");
export const createAdmin = (email: string, password: string, role: Role, totp: string) =>
  api<Staff>("/admin/admins", { method: "POST", body: { email, password, role }, totp });
export const changeRole = (id: number, role: Role, totp: string) =>
  api<Staff>(`/admin/admins/${id}/role`, { method: "PATCH", body: { role }, totp });
export const disableAdmin = (id: number) => api<Staff>(`/admin/admins/${id}/disable`, { method: "PATCH", body: {} });
export const resetTotp = (id: number, totp: string) => api<Staff>(`/admin/admins/${id}/totp/reset`, { method: "POST", body: {}, totp });
