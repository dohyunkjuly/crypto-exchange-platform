import type { Locale } from "./locales";
import type { Messages } from "./index";

/** Dynamic imports so a locale's dictionary is only in the bundle for pages that use it. */
const loaders: Record<Locale, () => Promise<Messages>> = {
  en: () => import("./messages/en.json").then((m) => m.default),
  ko: () => import("./messages/ko.json").then((m) => m.default),
};

export const getMessages = (locale: Locale) => loaders[locale]();
