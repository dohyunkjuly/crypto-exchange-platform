"use client";

import { useQuery } from "@tanstack/react-query";
import { Empty } from "@/components/ui/Empty";
import { useState } from "react";
import { useDescribe } from "@/components/ui/FormError";
import { useSession } from "@/features/auth/session";
import { useFavoritesOf } from "@/lib/favorites";
import { Menu, MenuItem } from "@/components/ui/Menu";
import { useT } from "@/lib/i18n";
import { LocaleLink } from "@/lib/i18n/LocaleLink";
import { fetchFollows, fetchLead, fetchLeaders, type Leader, type Terms } from "./api";
import { Faq } from "./Faq";
import { Pager, usePage } from "@/features/my/shared/Pager";

type Tab = "all" | "favorites";
/** The window the cards' figures are read over. */
export type Period = "days7" | "days30" | "days90";
export const PERIODS: Period[] = ["days7", "days30", "days90"];
/** What the grid is sorted by: a figure of the window, or one that has no window. */
type Order = "return" | "pnl" | "maxDrawdown" | "fundsCopying" | "followers";
/** Cards a page: four rows of the desktop grid's three. */
const PER_PAGE = 12;
const ORDERS: Order[] = ["return", "pnl", "maxDrawdown", "fundsCopying", "followers"];
/** Best first: the smallest drawdown, the largest of everything else. A leader with no figures goes last, not between. */
function rank(l: Leader, period: Period, by: Order): number {
  if (by === "followers") return l.followers;
  const v = by === "fundsCopying" ? l.stats?.fundsCopying : l.stats?.[period][by];
  if (v === null || v === undefined) return -Infinity;
  return by === "maxDrawdown" ? -Number(v) : Number(v);
}
const button = "inline-flex h-10 items-center justify-center rounded-lg px-4 text-base font-medium";
const outlined = `${button} border border-border hover:border-border-strong`;

/**
 * Who can be followed, as cards sorted by their 30-day return: the figures a follower chooses
 * by, and one action. Public, like the markets; a signed-in customer also sees what they have
 * copying and the way to their own copy accounts.
 */
export function Leaders() {
  const t = useT();
  const describe = useDescribe();
  const session = useSession();
  const signedIn = session.status === "signedIn";
  const leaders = useQuery({ queryKey: ["copy-leaders"], queryFn: ({ signal }) => fetchLeaders(signal), staleTime: 60_000 });
  const follows = useQuery({ queryKey: ["copy-follows"], queryFn: ({ signal }) => fetchFollows(signal), enabled: signedIn });
  const lead = useQuery({ queryKey: ["copy-lead"], queryFn: ({ signal }) => fetchLead(signal), enabled: signedIn, staleTime: 60_000 });
  const [favorites, toggle] = useFavoritesOf("favorite-leaders");
  const [tab, setTab] = useState<Tab>("all");
  const [name, setName] = useState("");
  const [period, setPeriod] = useState<Period>("days30");
  const [order, setOrder] = useState<Order>("return");
  // Sorted and searched here, over the whole list the server sent, so the pages are over that.
  const sorted = [...(leaders.data?.leaders ?? [])].sort((a, b) => rank(b, period, order) - rank(a, period, order));
  const typed = name.trim().toLowerCase();
  const shown = sorted.filter((l) => (tab === "all" || favorites.has(l.id)) && (!typed || l.displayName.toLowerCase().includes(typed)));
  const page = usePage(shown, PER_PAGE);

  if (leaders.error) return <p role="alert" className="py-8 text-center text-base text-muted">{describe(leaders.error)}</p>;
  if (!leaders.data) return <p className="py-8 text-center text-base text-dim" aria-busy>{t("common.loading")}</p>;

  const { terms } = leaders.data;
  // What the copy accounts hold, each coin valued at its market's last trade: the server's number.
  const copying = follows.data?.filter((f) => f.status === "ACTIVE").reduce((sum, f) => sum + Number(f.value), 0);

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-xl font-semibold">{t("copy.title")}</h1>
        {/* The same door, named for where it leads: a leader has an account to look at, anyone else a step to take. */}
        <LocaleLink href="/copy/lead" className={outlined}>{t(lead.data?.lead?.hasAccount ? "copy.leadAccount" : "copy.beLeader")}</LocaleLink>
      </div>
      {signedIn && (
        <div className="flex flex-wrap items-end justify-between gap-4 rounded-lg border border-border p-5">
          <div className="flex flex-col gap-1">
            <span className="text-sm text-muted">{t("copy.copyingBalance", { asset: terms.quoteAsset })}</span>
            <span className="num text-2xl font-semibold">{copying === undefined ? "—" : copying.toLocaleString(undefined, { maximumFractionDigits: 2 })}</span>
          </div>
          <LocaleLink href="/copy/mine" className={outlined}>{t("copy.copyOverview")}</LocaleLink>
        </div>
      )}

      <div className="flex flex-col gap-4">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div role="tablist" className="flex gap-6">
            {(["all", "favorites"] as const).map((x) => (
              <button key={x} role="tab" aria-selected={tab === x} onClick={() => setTab(x)}
                      className={`border-b-2 pb-2 text-base font-medium ${tab === x ? "border-text text-text" : "border-transparent text-muted hover:text-text"}`}>
                {t(x === "all" ? "copy.all" : "copy.favorites")}
              </button>
            ))}
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <label className="flex h-9 w-56 items-center gap-2 rounded-lg bg-surface-raised px-3 focus-within:ring-1 focus-within:ring-border-strong">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden className="shrink-0 text-dim"><circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" /></svg>
              <input value={name} onChange={(e) => setName(e.target.value)} placeholder={t("copy.searchName")} aria-label={t("copy.searchName")}
                     className="w-full bg-transparent text-sm text-text outline-none placeholder:text-dim" />
            </label>
            <Choice label={t("copy.period")} value={period} onChange={setPeriod} options={PERIODS.map((p) => ({ value: p, label: t(`copyPeriod.${p}`) }))} />
            <Choice label={t("copy.sortBy")} value={order} onChange={setOrder} options={ORDERS.map((o) => ({ value: o, label: t(`copySort.${o}`) }))} />
          </div>
        </div>
        {shown.length === 0
          ? <Empty className="mt-4 min-h-80">{t(typed ? "copy.noNameMatch" : tab === "all" ? "copy.noLeaders" : "copy.noFavorites")}</Empty>
          : (
            <>
              <ul className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
                {page.rows.map((l) => <LeaderCard key={l.id} leader={l} terms={terms} period={period} signedIn={signedIn} starred={favorites.has(l.id)} onStar={() => toggle(l.id)} />)}
              </ul>
              <div className="mt-6"><Pager current={page.current} pages={page.pages} onPage={page.setPage} /></div>
            </>
          )}
      </div>

      {/* The home page's 96px under its FAQ: this layout's main already gives 32 of it. */}
      <div className="pt-10 pb-16">
        <Faq terms={terms} />
      </div>
    </div>
  );
}

/** One leader: who, the month's result with its line, the figures a follower weighs, and Copy. */
function LeaderCard({ leader: l, terms, period, signedIn, starred, onStar }: {
  leader: Leader; terms: Terms; period: Period; signedIn: boolean; starred: boolean; onStar: () => void;
}) {
  const t = useT();
  const s = l.stats;
  const w = s?.[period];
  const pnl = w?.pnl ?? "0";
  const pnlTone = Number(pnl) === 0 ? "text-text" : Number(pnl) > 0 ? "text-up" : "text-down";
  const row = (label: string, value: React.ReactNode) => (
    <div className="flex items-center justify-between text-base"><span className="text-muted">{label}</span><span className="num">{value}</span></div>
  );
  return (
    <li className="group flex flex-col gap-4 rounded-lg border border-border p-4 transition-[border-color,transform,box-shadow] hover:-translate-y-0.5 hover:border-border-strong hover:shadow-lg hover:shadow-black/5"
        aria-label={l.displayName}>
      <div className="flex items-start justify-between gap-3">
        <LocaleLink href={`/copy/${l.id}`} className="flex min-w-0 items-center gap-3">
          <span aria-hidden className="flex size-11 shrink-0 items-center justify-center rounded-full bg-surface-raised text-lg font-semibold">{l.displayName.slice(0, 1).toUpperCase()}</span>
          <span className="flex min-w-0 flex-col">
            <span className="truncate text-base font-semibold transition-colors group-hover:underline group-hover:underline-offset-4">{l.displayName}</span>
            <span className="num text-sm text-muted">{t("copy.followers")} {l.followers.toLocaleString()} / {terms.maxFollowers.toLocaleString()}</span>
          </span>
        </LocaleLink>
        <button type="button" onClick={onStar} aria-pressed={starred} aria-label={t(starred ? "copy.unfavorite" : "copy.favorite")}
                className={`-mr-1 -mt-1 p-1 text-lg leading-none ${starred ? "text-text" : "text-dim hover:text-text"}`}>{starred ? "★" : "☆"}</button>
      </div>

      <div className="flex items-end justify-between gap-3">
        <div className="flex flex-col">
          <span className="text-sm text-muted">{t("copy.pnlOver", { days: t(`copyPeriod.${period}`), asset: terms.quoteAsset })}</span>
          <span className={`num text-2xl font-semibold ${pnlTone}`}>{`${Number(pnl) > 0 ? "+" : ""}${Number(pnl).toLocaleString(undefined, { maximumFractionDigits: 2 })}`}</span>
          <span className="text-sm text-muted">{t("copy.returnShort")} <Percent value={w?.return ?? null} signed /></span>
        </div>
        <Sparkline values={(s?.series ?? []).map((p) => p.value)} />
      </div>

      <div className="flex flex-col gap-1.5">
        {row(t("copy.fundsCopying"), `${Number(s?.fundsCopying ?? 0).toLocaleString(undefined, { maximumFractionDigits: 2 })} ${terms.quoteAsset}`)}
        {row(t("copy.mddOver", { days: t(`copyPeriod.${period}`) }), <Percent value={w?.maxDrawdown ?? null} />)}
      </div>

      {/* A visitor goes straight to the login: the form behind Copy needs an account to fund from. */}
      {l.accepting
        ? <LocaleLink href={signedIn ? `/copy/${l.id}` : "/login"} className={`${button} bg-text text-bg hover:opacity-85`}>{t("copy.copyAction")}</LocaleLink>
        : <span className={`${button} border border-border text-dim`}>{t("copy.full")}</span>}
    </li>
  );
}

/** One choice from a few, as a pill that opens a list: what is picked is what the pill says. */
export function Choice<V extends string>({ label, value, options, onChange }: {
  label: string; value: V; options: { value: V; label: string }[]; onChange: (v: V) => void;
}) {
  const current = options.find((o) => o.value === value)?.label ?? "";
  return (
    <Menu label={label} align="right"
          trigger={(open) => (
            <span className={`flex h-9 items-center gap-1.5 rounded-lg bg-surface-raised px-3 text-sm font-medium ${open ? "text-text" : "text-text hover:opacity-80"}`}>
              {current}
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden className={`text-muted transition-transform ${open ? "rotate-180" : ""}`}><path d="m6 9 6 6 6-6" /></svg>
            </span>
          )}>
      {(close) => options.map((o) => (
        <MenuItem key={o.value} role="menuitemradio" checked={value === o.value} onSelect={() => { onChange(o.value); close(); }}>{o.label}</MenuItem>
      ))}
    </Menu>
  );
}

/** The month's line: cumulative return per snapshot, drawn to fit. A flat line at zero until there are two points. */
function Sparkline({ values }: { values: string[] }) {
  const t = useT();
  const w = 96; const h = 36;
  const nums = values.length < 2 ? [0, 0] : values.map(Number);
  if (nums.every((v) => v === 0)) {
    return (
      <svg viewBox={`0 0 ${w} ${h}`} width={w} height={h} className="shrink-0 text-up" role="img" aria-label={t("copy.sparkline")}>
        <line x1="0" x2={w} y1={h / 2} y2={h / 2} stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
      </svg>
    );
  }
  const min = Math.min(...nums); const max = Math.max(...nums);
  const span = max - min || 1;
  const points = nums.map((v, i) => `${(i / (nums.length - 1)) * w},${h - 2 - ((v - min) / span) * (h - 4)}`).join(" ");
  const up = nums[nums.length - 1] >= nums[0];
  return (
    <svg viewBox={`0 0 ${w} ${h}`} width={w} height={h} className={`shrink-0 ${up ? "text-up" : "text-down"}`} role="img" aria-label={t("copy.sparkline")}>
      <polyline points={points} fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  );
}

/**
 * A figure from the nightly run, 0% until there is something to measure, so a new leader's card
 * reads like every other. `signed`: a return, coloured by its sign.
 */
export function Percent({ value, signed = false }: { value: string | null; signed?: boolean }) {
  if (value === null) return <span className="num">0.00%</span>;
  const n = Number(value);
  const tone = !signed || n === 0 ? "" : n > 0 ? "text-up" : "text-down";
  return <span className={`num ${tone}`}>{signed && n > 0 ? "+" : ""}{value}%</span>;
}
