"use client";

import { useT } from "@/lib/i18n";

type Variant = "primary" | "secondary" | "ghost" | "up" | "down";
type Size = "sm" | "md" | "lg";

/**
 * Hierarchy by fill, never hue -- there is no accent colour. `up`/`down` exist only for
 * Buy/Sell, the one control allowed to carry a price colour.
 */
const styles: Record<Variant, string> = {
  primary: "bg-text text-bg hover:opacity-85",
  secondary: "bg-surface-raised text-text border border-border hover:border-border-strong",
  ghost: "text-muted hover:text-text",
  up: "bg-up text-white hover:opacity-90",
  down: "bg-down text-white hover:opacity-90",
};

/**
 * Height belongs to a prop, not to `className`. Tailwind resolves two height utilities by
 * their order in the stylesheet, not by the order they appear in the class string, and `h-10`
 * is emitted after `h-7`, `h-8` and `h-9` -- so a caller passing a smaller height silently got
 * this one. Larger ones happened to work, which is worse than if none had.
 */
const sizes: Record<Size, string> = {
  sm: "h-8 px-3",
  md: "h-10 px-4",
  lg: "h-11 px-4",
};

export function Button({
  variant = "primary",
  size = "md",
  pending,
  block,
  className = "",
  children,
  ...props
}: {
  variant?: Variant;
  size?: Size;
  pending?: boolean;
  block?: boolean;
} & React.ButtonHTMLAttributes<HTMLButtonElement>) {
  const t = useT();
  return (
    <button
      {...props}
      disabled={props.disabled || pending}
      aria-busy={pending || undefined}
      className={`inline-flex ${sizes[size]} items-center justify-center rounded-lg text-sm font-medium
        transition-[opacity,border-color] outline-none
        focus-visible:ring-2 focus-visible:ring-border-strong focus-visible:ring-offset-2 focus-visible:ring-offset-bg
        disabled:cursor-not-allowed disabled:opacity-50
        ${block ? "w-full" : ""} ${styles[variant]} ${className}`}
    >
      {pending ? t("common.working") : children}
    </button>
  );
}
