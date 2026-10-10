"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { AdminApiError, api } from "./api";

export type Admin = { id: number; email: string; role: "VIEWER" | "OPERATOR" | "SUPERADMIN" };

/**
 * Who is logged in, asked of admin-bff on every page: a cookie being present proves nothing,
 * only admin-bff can tell (docs/reference/frontend-session.md). A 401, including the
 * half-logged-in PENDING session, sends the admin to the login.
 */
export function useAdmin(): Admin | null {
  const router = useRouter();
  const [admin, setAdmin] = useState<Admin | null>(null);
  useEffect(() => {
    let cancelled = false;
    api<Admin>("/admin/auth/me")
      .then((me) => { if (!cancelled) setAdmin(me); })
      .catch((e: unknown) => {
        if (e instanceof AdminApiError && e.notAuthenticated) router.replace("/login");
      });
    return () => { cancelled = true; };
  }, [router]);
  return admin;
}

export async function logout(): Promise<void> {
  await api("/admin/auth/logout", { method: "POST", body: {} });
}
