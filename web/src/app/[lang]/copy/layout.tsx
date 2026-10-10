import { AppFooter } from "@/features/shell/AppFooter";
import { AppHeader } from "@/features/shell/AppHeader";

/** Copy trading's public pages: who can be followed. No session gate -- a leaderboard that
 *  needs a login has nothing to show a visitor. A copy account's own pages are under /my. */
export default function CopyLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-dvh flex-col">
      <AppHeader />
      <main className="mx-auto w-full min-w-0 max-w-7xl flex-1 px-5 py-8">{children}</main>
      <AppFooter />
    </div>
  );
}
