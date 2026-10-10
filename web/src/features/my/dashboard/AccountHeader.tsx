"use client";

import { useQueries } from "@tanstack/react-query";
import { Badge } from "@/components/ui/Badge";
import { useSession } from "@/features/auth/session";
import { useT } from "@/lib/i18n";
import { fetchFees, fetchKyc, fetchMfa } from "../shared/api";
import { CopyButton } from "../shared/CopyButton";

/**
 * Who is signed in, and the three things about this account that change what it may do:
 * its fee tier, how far its identity check got, and whether a second factor is on.
 *
 * <p>Deliberately not the social version of this header. Followers and a handle mean
 * nothing on a white-label exchange; the fee tier a user is actually charged does.
 */
export function AccountHeader() {
  const t = useT();
  const session = useSession();
  const [kyc, mfa, fees] = useQueries({
    queries: [
      { queryKey: ["kyc"], queryFn: ({ signal }: { signal: AbortSignal }) => fetchKyc(signal) },
      { queryKey: ["mfa"], queryFn: ({ signal }: { signal: AbortSignal }) => fetchMfa(signal) },
      { queryKey: ["fees"], queryFn: ({ signal }: { signal: AbortSignal }) => fetchFees(signal) },
    ],
  });
  if (session.status !== "signedIn") return null;
  const user = session.user;
  // A name if they gave one; otherwise the local part, which is what they typed to sign in.
  const display = user.name?.trim() || user.email.split("@")[0];
  const verified = kyc.data?.status === "APPROVED" && kyc.data.level !== "NONE";

  return (
    <section className="flex flex-wrap items-center gap-x-10 gap-y-5 rounded-lg border border-border p-5">
      <div className="flex items-center gap-4">
        <span aria-hidden
              className="inline-flex h-12 w-12 shrink-0 items-center justify-center rounded-full
                         bg-surface-raised text-lg font-semibold text-muted">
          {display.charAt(0).toUpperCase()}
        </span>
        <div className="min-w-0">
          <div className="truncate text-lg font-semibold">{display}</div>
          <div className="truncate text-sm text-muted">{user.email}</div>
        </div>
      </div>

      <Stat label={t("dashboard.uid")}>
        <span className="num">{user.id}</span>
        <CopyButton text={String(user.id)} />
      </Stat>
      <Stat label={t("dashboard.feeTier")}>
        {fees.data ? <span>{fees.data.tierName}</span> : <span className="text-dim">—</span>}
      </Stat>
      <Stat label={t("dashboard.verification")}>
        {kyc.data && <Badge muted={!verified}>{t(`kycLevel.${kyc.data.level}` as "kycLevel.NONE")}</Badge>}
      </Stat>
      <Stat label={t("dashboard.twoFactor")}>
        {mfa.data && <Badge muted={!mfa.data.enabled}>{t(mfa.data.enabled ? "security.on" : "security.off")}</Badge>}
      </Stat>
    </section>
  );
}

function Stat({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <div className="text-sm text-muted">{label}</div>
      <div className="flex items-center gap-2 text-base">{children}</div>
    </div>
  );
}
