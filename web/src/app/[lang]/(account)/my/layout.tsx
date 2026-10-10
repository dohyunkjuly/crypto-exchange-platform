import { Sidebar } from "@/features/my/shared/Sidebar";

/**
 * The account area: rail on the left, page on the right. The session gate is one level up.
 *
 * <p>The rail stays on the window's edge, under the wordmark, but the page beside it stops
 * growing at 80rem: a table stretched across a wide monitor puts its first and last column
 * so far apart that a row stops reading as one row. Past the point where 75% of the window
 * is wider than that, the page goes back to growing with it -- a fixed cap on a very wide
 * screen leaves a gap beside the rail bigger than the rail.
 */
export default function MyLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-6 lg:flex-row lg:gap-10">
      <Sidebar />
      {/* The cap is only for the row layout; stacked under lg the page takes the width it has. */}
      {/* `lg:mx-auto`, never plain `mx-auto`: in the column this is below lg, an auto side margin
          overrides the stretch and the page shrinks to the width of its widest word. */}
      <div className="min-w-0 flex-1 lg:mx-auto lg:max-w-[max(80rem,75%)]">{children}</div>
    </div>
  );
}
