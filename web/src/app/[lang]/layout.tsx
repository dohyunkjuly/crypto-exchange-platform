import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import { notFound } from "next/navigation";
import { Providers } from "../providers";
import { ToastProvider } from "@/components/ui/Toast";
import { I18nProvider } from "@/lib/i18n";
import { LOCALES, hasLocale } from "@/lib/i18n/locales";
import { getMessages } from "@/lib/i18n/server";
import { EXCHANGE_NAME } from "@/lib/env";
import "../globals.css";

const geistSans = Geist({ variable: "--font-geist-sans", subsets: ["latin"] });
const geistMono = Geist_Mono({ variable: "--font-geist-mono", subsets: ["latin"] });

export const metadata: Metadata = {
  title: { default: EXCHANGE_NAME, template: `%s · ${EXCHANGE_NAME}` },
  description: "Spot trading",
};

/** Only these two are built; anything else in the segment is a 404, not a runtime guess. */
export const dynamicParams = false;
export function generateStaticParams() {
  return LOCALES.map((lang) => ({ lang }));
}

export default async function RootLayout({ children, params }: LayoutProps<"/[lang]">) {
  const { lang } = await params;
  if (!hasLocale(lang)) notFound();
  const messages = await getMessages(lang);
  return (
    <html
      lang={lang}
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <body className="min-h-full flex flex-col bg-bg text-text">
        {/* Runs before hydration so a light-mode viewer never sees a dark flash. Dark is the
            default and the absence of the attribute, so only "light" needs writing. */}
        <script
          dangerouslySetInnerHTML={{
            __html: "try{if(localStorage.getItem('theme')==='light')document.documentElement.dataset.theme='light'}catch(e){}",
          }}
        />
        <Providers>
          <I18nProvider locale={lang} messages={messages}>
            <ToastProvider>{children}</ToastProvider>
          </I18nProvider>
        </Providers>
      </body>
    </html>
  );
}
