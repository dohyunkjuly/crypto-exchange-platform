import { RequireSession } from "@/features/auth/RequireSession";
import { AppFooter } from "@/features/shell/AppFooter";
import { AppHeader } from "@/features/shell/AppHeader";
import { UserStream } from "@/features/my/shared/UserStream";

/** Everything behind a session. The gate lives here so a new route under it cannot forget it;
 *  the private socket lives here so it opens once for the whole signed-in area. */
export default function AccountLayout({ children }: { children: React.ReactNode }) {
  return (
    <RequireSession>
      <UserStream>
        <div className="flex min-h-dvh flex-col">
          <AppHeader />
          <main className="w-full min-w-0 flex-1 px-3 py-8 sm:px-6">{children}</main>
          <AppFooter />
        </div>
      </UserStream>
    </RequireSession>
  );
}
