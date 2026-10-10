import type { Metadata } from "next";
import "./globals.css";
import { EXCHANGE_NAME } from "@/lib/env";

export const metadata: Metadata = {
  title: { default: `${EXCHANGE_NAME} Admin`, template: `%s · ${EXCHANGE_NAME} Admin` },
  robots: { index: false, follow: false },
};

/** The system font stack, not a web font: a staff tool on an internal network has nothing to gain
 *  from fetching one, and the platform's own face reads best at the sizes a console uses. */
export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className="h-full">
      <body className="min-h-full flex flex-col bg-bg text-text">{children}</body>
    </html>
  );
}
