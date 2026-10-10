/**
 * The one icon per account section, drawn once and used by both the sidebar rail and the
 * account menu -- the same picture in both places is what makes them read as one navigation.
 *
 * Line icons on `currentColor` at 1.6 stroke: heavier competes with the text beside it, and a
 * filled glyph would be the only solid shape in a monochrome UI.
 */
export type NavIconName = "dashboard" | "assets" | "orders" | "copy" | "account" | "subAccounts" | "signOut" | "verify";

const PATHS: Record<NavIconName, React.ReactNode> = {
  // Four panes: the overview.
  dashboard: <><rect x="3" y="3" width="7" height="7" rx="1.5" /><rect x="14" y="3" width="7" height="7" rx="1.5" /><rect x="3" y="14" width="7" height="7" rx="1.5" /><rect x="14" y="14" width="7" height="7" rx="1.5" /></>,
  // A wallet: what the account holds.
  assets: <><rect x="3" y="6" width="18" height="13" rx="2" /><path d="M3 10h18" /><circle cx="17" cy="14.5" r="1" /></>,
  // A list on a page: the order book of one's own.
  orders: <><rect x="4" y="3" width="16" height="18" rx="2" /><path d="M8 8h8M8 12h8M8 16h5" /></>,
  // One line followed by another: a trade and its copy.
  copy: <><path d="M3 15l5-5 4 3 6-7" /><path d="M3 20l5-5 4 3 6-7" opacity="0.5" /><path d="M15 6h3v3" /></>,
  // A person: the identity itself.
  account: <><circle cx="12" cy="8" r="4" /><path d="M4 21a8 8 0 0 1 16 0" /></>,
  // A person and the accounts beside them.
  subAccounts: <><circle cx="9" cy="8" r="3.5" /><path d="M3 20a6 6 0 0 1 12 0" /><path d="M17 9h4M17 13h4M17 17h4" /></>,
  signOut: <><path d="M15 17l5-5-5-5" /><path d="M20 12H9" /><path d="M12 19H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h6" /></>,
  verify: <><circle cx="12" cy="12" r="9" /><path d="M12 8v5M12 16h.01" /></>,
};

export function NavIcon({ name, size = 18 }: { name: NavIconName; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6"
         strokeLinecap="round" strokeLinejoin="round" aria-hidden className="shrink-0">
      {PATHS[name]}
    </svg>
  );
}
