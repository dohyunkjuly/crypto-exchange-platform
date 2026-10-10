// Renders content/*.md into build/. One page per top-level markdown file; index.md pulls in
// the includes its front matter lists. A fenced code block or a "> " quote goes to the dark
// right column, everything else to the left, and blocks in the front matter's languages
// become the tabs at the top of the right column.
import { readFile, writeFile, mkdir, rm, copyFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import matter from "gray-matter";
import MarkdownIt from "markdown-it";
import { createHighlighter } from "shiki";

const root = path.dirname(fileURLToPath(import.meta.url));
const contentDir = path.join(root, "content");
const siteDir = path.join(root, "site");
const outDir = path.join(root, "build");

// Blocks labelled with one of these show only under their tab. Anything else (json, text) is
// always visible. Written as "javascript" in the markdown, highlighted as TypeScript.
const HIGHLIGHT_LANG = { shell: "shell", python: "python", javascript: "typescript", json: "json", text: "text" };

const highlighter = await createHighlighter({
  themes: ["one-dark-pro"],
  langs: ["shell", "python", "typescript", "json"],
});

const template = await readFile(path.join(siteDir, "template.html"), "utf8");

const escapeHtml = (s) =>
  s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

const slug = (text) =>
  text.toLowerCase().replace(/<[^>]*>/g, "").replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");

function renderPage(markdown, tabs) {
  const tabLangs = new Set(tabs.map((t) => t.lang));
  const md = new MarkdownIt({ html: true });
  const toc = []; // { level, text, id }
  const seen = new Map();

  md.renderer.rules.fence = (tokens, idx) => {
    const token = tokens[idx];
    const lang = token.info.trim() || "text";
    const highlightAs = HIGHLIGHT_LANG[lang] ?? "text";
    const html = highlightAs === "text"
      ? `<pre class="shiki"><code>${escapeHtml(token.content)}</code></pre>`
      : highlighter.codeToHtml(token.content, { lang: highlightAs, theme: "one-dark-pro" });
    const attr = tabLangs.has(lang) ? ` data-lang="${lang}"` : "";
    return `<div class="code"${attr}><button class="copy" type="button" title="Copy">` +
      `<svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true"><rect x="5" y="5" width="9" height="9" rx="1.5" fill="none" stroke="currentColor" stroke-width="1.4"/><path d="M11 5V3.5A1.5 1.5 0 0 0 9.5 2h-6A1.5 1.5 0 0 0 2 3.5v6A1.5 1.5 0 0 0 3.5 11H5" fill="none" stroke="currentColor" stroke-width="1.4"/></svg>` +
      `</button>${html}</div>\n`;
  };

  md.renderer.rules.heading_open = (tokens, idx) => {
    const token = tokens[idx];
    const level = Number(token.tag.slice(1));
    const text = tokens[idx + 1].content;
    let id = slug(text);
    // Two headings with one name ("Authentication" under REST and under the socket) must not
    // share an anchor, or the second is unreachable from the sidebar.
    const n = seen.get(id) ?? 0;
    seen.set(id, n + 1);
    if (n > 0) id = `${id}-${n + 1}`;
    if (level <= 2) toc.push({ level, text, id });
    return `<${token.tag} id="${id}">`;
  };

  // "**Rate limit:** weight 10", "**HTTP request**": a paragraph that opens with bold text is
  // one of the endpoint labels, laid out with room around it.
  md.renderer.rules.paragraph_open = (tokens, idx) => {
    const inline = tokens[idx + 1];
    const first = inline?.children?.find((c) => c.type !== "text" || c.content !== "");
    return first?.type === "strong_open" ? '<p class="label">' : "<p>";
  };

  md.renderer.rules.table_open = () => '<div class="table"><table>';
  md.renderer.rules.table_close = () => "</table></div>";

  const body = md.render(markdown);
  return { body, toc };
}

// Text of each section, for the sidebar filter. Heading to heading, tags stripped.
function searchIndex(body, toc) {
  const plain = body.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ");
  return toc.map((entry, i) => {
    const start = plain.indexOf(entry.text);
    const next = toc[i + 1];
    const end = next ? plain.indexOf(next.text, start + 1) : plain.length;
    return { id: entry.id, text: plain.slice(start, end === -1 ? undefined : end).toLowerCase() };
  });
}

function sidebar(toc) {
  let html = "";
  let open = false;
  for (const entry of toc) {
    if (entry.level === 1) {
      if (open) html += "</ul></li>";
      html += `<li><a class="toc-link" href="#${entry.id}">${entry.text}</a><ul class="toc-sub">`;
      open = true;
    } else {
      html += `<li><a class="toc-link toc-sub-link" href="#${entry.id}">${entry.text}</a></li>`;
    }
  }
  if (open) html += "</ul></li>";
  return html;
}

// The operator's name, in the page titles and the logo's alt text. Nothing in this repo names
// an exchange (docs/reference/white-label.md); the same variable the apps read.
const exchangeName = (process.env.EXCHANGE_NAME ?? "").trim();
if (!exchangeName) {
  console.error("EXCHANGE_NAME is not set: the docs carry the operator's name in their titles.");
  process.exit(1);
}

async function build(file, out, { active }) {
  const raw = await readFile(path.join(contentDir, file), "utf8");
  const { data, content } = matter(raw);
  data.title = data.title.replaceAll("{name}", exchangeName);
  const tabs = (data.language_tabs ?? []).map((t) => {
    const [lang, label] = Object.entries(t)[0];
    return { lang, label };
  });
  const includes = await Promise.all(
    (data.includes ?? []).map((name) => readFile(path.join(contentDir, "includes", `_${name}.md`), "utf8")),
  );
  const { body, toc } = renderPage([content, ...includes].join("\n\n"), tabs);

  const html = template
    .replaceAll("{{title}}", escapeHtml(data.title))
    .replaceAll("{{name}}", escapeHtml(exchangeName))
    .replace("{{tabs}}", tabs.map((t, i) =>
      `<button type="button" class="tab${i === 0 ? " active" : ""}" data-lang="${t.lang}">${t.label}</button>`).join(""))
    .replace("{{tabs-class}}", tabs.length ? "" : " hidden")
    .replace("{{nav-api}}", active === "api" ? " active" : "")
    .replace("{{nav-log}}", active === "log" ? " active" : "")
    .replace("{{sidebar}}", sidebar(toc))
    .replace("{{content}}", body)
    .replace("{{index}}", JSON.stringify(searchIndex(body, toc)));
  await writeFile(path.join(outDir, out), html);
}

// Inter, the page's typeface, from the @fontsource/inter package: three weights, Latin only.
const fontDir = path.join(root, "node_modules", "@fontsource", "inter", "files");
const fonts = ["400", "500", "600"].map((w) => `inter-latin-${w}-normal.woff2`);

await rm(outDir, { recursive: true, force: true });
await mkdir(path.join(outDir, "fonts"), { recursive: true });
await Promise.all([
  ...fonts.map((f) => copyFile(path.join(fontDir, f), path.join(outDir, "fonts", f))),
  build("index.md", "index.html", { active: "api" }),
  build("log.md", "log.html", { active: "log" }),
  copyFile(path.join(siteDir, "style.css"), path.join(outDir, "style.css")),
  copyFile(path.join(siteDir, "app.js"), path.join(outDir, "app.js")),
  // The brand files. The two icons are the web app's own (web/src/app), copied here so this
  // folder builds on its own.
  ...["logo.svg", "favicon.svg", "apple-icon.png"].map((f) =>
    copyFile(path.join(contentDir, f), path.join(outDir, f))),
]);
console.log("built build/index.html and build/log.html");
