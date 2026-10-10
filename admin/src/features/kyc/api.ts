import { api } from "@/lib/api";
import type { Summary } from "@/features/users/api";

export type KycStatus = "PENDING" | "APPROVED" | "REJECTED" | "EXPIRED";
export type Level = "BASIC" | "INTERMEDIATE" | "ADVANCED";

/** What the customer typed. Documents are with the provider, never here. */
export type Submission = {
  user: Summary; status: KycStatus | "NOT_STARTED" | "IN_REVIEW"; level: string;
  legalFirstName: string | null; legalLastName: string | null; dateOfBirth: string | null;
  nationality: string | null; residenceCountry: string | null;
  submittedAt: string | null; reviewedAt: string | null; expiresAt: string | null; rejectionReason: string | null;
};
export type Queue = { items: Submission[]; total: number; page: number; size: number };

export const fetchQueue = (status: KycStatus, page: number, size = 25) =>
  api<Queue>(`/admin/kyc?status=${status}&page=${page}&size=${size}`);
export const approveKyc = (userId: number, level: Level, expiresAt: string | null, totp: string) =>
  api<Submission>(`/admin/kyc/${userId}/approve`, { method: "POST", body: { level, expiresAt }, totp });
export const rejectKyc = (userId: number, reason: string, totp: string) =>
  api<Submission>(`/admin/kyc/${userId}/reject`, { method: "POST", body: { reason }, totp });
