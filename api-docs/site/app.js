// Sidebar scroll-spy, language tabs, copy buttons, search. No dependencies.
(() => {
  const toc = document.getElementById("toc");
  const links = [...toc.querySelectorAll(".toc-link")];
  const headings = links.map((a) => document.getElementById(a.hash.slice(1)));
  const topbar = 44;

  // ---- Scroll-spy: highlight the heading above the top of the viewport, and show only the
  // active section's subsections, so the sidebar stays short. Instant, not animated: rows that
  // move under the cursor land a click on the wrong row.
  let current = null;
  function refresh(initial) {
    const y = window.scrollY + topbar + 16;
    const bottom = window.scrollY + window.innerHeight >= document.body.scrollHeight - 2;
    let best = 0;
    for (let i = 0; i < headings.length; i++) {
      if (headings[i] && headings[i].offsetTop <= y) best = i;
    }
    if (bottom) best = headings.length - 1;
    if (best === current) return;
    current = best;
    links.forEach((a) => a.classList.remove("active"));
    toc.querySelectorAll(".toc-sub").forEach((ul) => ul.classList.remove("open"));
    const link = links[best];
    link.classList.add("active");
    const sub = link.classList.contains("toc-sub-link") ? link.closest(".toc-sub") : link.nextElementSibling;
    if (sub) sub.classList.add("open");
    // Not on the first pass: the browser has not scrolled to the URL's own hash yet, and
    // rewriting it here would send the reader to the top instead.
    if (!initial) history.replaceState(null, "", "#" + link.hash.slice(1));
  }
  let ticking = false;
  window.addEventListener("scroll", () => {
    if (ticking) return;
    ticking = true;
    requestAnimationFrame(() => { ticking = false; refresh(); });
  });
  window.addEventListener("resize", () => refresh());
  const target = location.hash && document.getElementById(location.hash.slice(1));
  if (target) target.scrollIntoView();
  refresh(true);

  // ---- Language tabs: one language visible at a time, remembered per browser.
  const tabs = [...document.querySelectorAll(".tab")];
  const blocks = [...document.querySelectorAll(".code[data-lang]")];
  function select(lang) {
    tabs.forEach((t) => t.classList.toggle("active", t.dataset.lang === lang));
    blocks.forEach((b) => { b.hidden = b.dataset.lang !== lang; });
    try { localStorage.setItem("docs-lang", lang); } catch { /* private mode */ }
  }
  let saved = null;
  try { saved = localStorage.getItem("docs-lang"); } catch { /* private mode */ }
  if (tabs.length) {
    select(tabs.some((t) => t.dataset.lang === saved) ? saved : tabs[0].dataset.lang);
    tabs.forEach((t) => t.addEventListener("click", () => {
      // Keep the reader's place: the page above shrinks or grows when blocks change.
      const anchor = headings[current];
      const before = anchor ? anchor.getBoundingClientRect().top : 0;
      select(t.dataset.lang);
      if (anchor) window.scrollBy(0, anchor.getBoundingClientRect().top - before);
    }));
  }

  // ---- Copy buttons
  document.querySelectorAll(".copy").forEach((button) => {
    button.addEventListener("click", async () => {
      const code = button.parentElement.querySelector("code").innerText;
      try {
        await navigator.clipboard.writeText(code);
        button.classList.add("done");
        setTimeout(() => button.classList.remove("done"), 1200);
      } catch { /* clipboard blocked */ }
    });
  });

  // ---- Search: filter the sidebar to sections whose text contains every word typed.
  const index = JSON.parse(document.getElementById("search-index").textContent);
  const byId = new Map(index.map((s) => [s.id, s.text]));
  const input = document.getElementById("search");
  input.addEventListener("input", () => {
    const words = input.value.toLowerCase().split(/\s+/).filter(Boolean);
    const items = toc.querySelectorAll("li");
    if (!words.length) {
      items.forEach((li) => li.classList.remove("hidden"));
      toc.querySelectorAll(".toc-sub").forEach((ul) => ul.classList.remove("open"));
      current = null;
      refresh(true);
      return;
    }
    items.forEach((li) => {
      const a = li.querySelector(":scope > .toc-link");
      const text = byId.get(a.hash.slice(1)) || "";
      const hit = words.every((w) => text.includes(w));
      li.classList.toggle("hidden", !hit);
    });
    // A section with a matching subsection stays visible, and its list opens.
    toc.querySelectorAll(":scope > li").forEach((li) => {
      const sub = li.querySelector(".toc-sub");
      const anyChild = sub && [...sub.children].some((c) => !c.classList.contains("hidden"));
      if (anyChild) { li.classList.remove("hidden"); sub.classList.add("open"); }
    });
  });

  // ---- Phone drawer
  const sidebar = document.getElementById("sidebar");
  const backdrop = document.getElementById("backdrop");
  const setOpen = (open) => { sidebar.classList.toggle("open", open); backdrop.classList.toggle("show", open); };
  document.getElementById("nav-tab").addEventListener("click", () => setOpen(!sidebar.classList.contains("open")));
  backdrop.addEventListener("click", () => setOpen(false));
  toc.addEventListener("click", (e) => { if (e.target.closest("a")) setOpen(false); });
})();
