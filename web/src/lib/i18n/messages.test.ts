import { describe, expect, it } from "vitest";
import en from "./messages/en.json";
import ko from "./messages/ko.json";

/** A key missing from one language renders as the key itself in production. Catch it here. */
function keys(o: object, prefix = ""): string[] {
  return Object.entries(o).flatMap(([k, v]) =>
    typeof v === "string" ? [prefix + k] : keys(v as object, `${prefix}${k}.`),
  );
}

describe("dictionaries", () => {
  it("have exactly the same keys", () => {
    expect(keys(ko).sort()).toEqual(keys(en).sort());
  });
  it("use the same placeholders per key", () => {
    const vars = (s: string) => (s.match(/\{\w+\}/g) ?? []).sort();
    const flat = (o: object, p = ""): Record<string, string> =>
      Object.fromEntries(Object.entries(o).flatMap(([k, v]) =>
        typeof v === "string" ? [[p + k, v]] : Object.entries(flat(v as object, `${p}${k}.`))));
    const e = flat(en), k = flat(ko);
    for (const key of Object.keys(e)) expect(vars(k[key]), key).toEqual(vars(e[key]));
  });
});
