import type { NavIconName } from "@/components/ui/NavIcon";
import type { MessageKey } from "@/lib/i18n";

export type NavItem = { href: string; key: MessageKey };
export type NavGroup = { id: string; key: MessageKey; icon: NavIconName; items: NavItem[] };

/**
 * Every account destination, once. The rail and the account menu both read it, so a new section
 * appears in both or in neither -- two lists that must agree is one list and a bug waiting.
 */
export const TOP: { href: string; key: MessageKey; icon: NavIconName }[] = [
  { href: "/my/dashboard", key: "menu.dashboard", icon: "dashboard" },
  { href: "/my/assets", key: "menu.assets", icon: "assets" },
  { href: "/my/orders", key: "menu.orders", icon: "orders" },
];

export const GROUPS: NavGroup[] = [
  { id: "account", key: "menu.account", icon: "account", items: [
    { href: "/my/account/identification", key: "menu.identification" },
    { href: "/my/account/security", key: "menu.security" },
    { href: "/my/account/api", key: "menu.api" },
  ] },
  { id: "sub-accounts", key: "menu.subAccounts", icon: "subAccounts", items: [
    { href: "/my/sub-accounts", key: "menu.subAccountManagement" },
    { href: "/my/sub-accounts/assets", key: "menu.subAssets" },
    { href: "/my/sub-accounts/api", key: "menu.subApi" },
    { href: "/my/sub-accounts/orders", key: "menu.subOrders" },
    { href: "/my/sub-accounts/transfers", key: "menu.subTransfers" },
  ] },
];

/** A list page is a prefix of its own sections, so it lights only on an exact match. */
export const isPrefixOfAnother = (item: NavItem) =>
  GROUPS.some((g) => g.items.some((other) => other !== item && other.href.startsWith(`${item.href}/`)));
