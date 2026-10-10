export type Vars = Record<string, string | number>;

/** `{name}` placeholders filled in. No "use client", so server shells can call it too. */
export function format(s: string, vars?: Vars): string {
  return vars ? s.replace(/\{(\w+)\}/g, (_, k) => (k in vars ? String(vars[k]) : `{${k}}`)) : s;
}
