"use strict";
(() => {
const $ = s => document.querySelector(s);
const $$ = s => [...document.querySelectorAll(s)];
const store = {
  get(k, d) { try { return localStorage.getItem(k) ?? d; } catch { return d; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch {} },
};
const S = { parts: [], byPn: new Map(), version: 0, publicUrl: location.origin, cat: "", sel: -1, list: [],
            open: null, view: "parts", labelSel: new Set(), me: null, roles: [], canManageUsers: false };
const can = p => !!S.me && S.me.perms.includes(p);
const cookie = k => { const m = document.cookie.match(new RegExp("(?:^|; )" + k + "=([^;]*)")); return m ? decodeURIComponent(m[1]) : ""; };
const userName = () => store.get("user", "") || cookie("sp_user");
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const norm = s => String(s ?? "").toUpperCase().replace(/[^A-Z0-9]/g, "");
const num = n => n == null ? "" : (Math.round(n * 100) / 100).toLocaleString("sv-SE");
const eur = n => n == null ? "—" : n.toLocaleString("sv-SE", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const eur0 = n => "€" + Math.round(n || 0).toLocaleString("sv-SE");

// ---------- API ----------
async function api(path, opts = {}) {
  const headers = { "Content-Type": "application/json" };
  const key = store.get("apikey", "");
  if (key) headers["X-Api-Key"] = key;
  const r = await fetch("/api/" + path, { ...opts, headers: { ...headers, ...(opts.headers || {}) },
    body: opts.body && typeof opts.body !== "string" && !(opts.body instanceof Blob) ? JSON.stringify(opts.body) : opts.body });
  const ct = r.headers.get("content-type") || "";
  const data = ct.includes("json") ? await r.json() : await r.text();
  if (r.status === 401) { location.href = "/login?next=" + encodeURIComponent(location.pathname + location.hash); throw new Error("auth"); }
  if (!r.ok) throw new Error(data.error || r.statusText);
  return data;
}
function toast(msg, err) {
  const t = $("#toast"); t.textContent = msg; t.className = "show" + (err ? " err" : "");
  clearTimeout(t._h); t._h = setTimeout(() => t.className = "", 2600);
}

async function loadMe() {
  const d = await api("me");
  S.me = d.user; S.roles = d.roles || []; S.canManageUsers = d.can_manage_users;
  const el = $("#whoami");
  el.hidden = false;
  el.innerHTML = `<div><b>${esc(S.me.name || S.me.username)}</b><small>${esc(S.me.role)}</small></div><a href="/logout" title="Sign out">⤴</a>`;
  document.body.dataset.role = S.me.role;
  $$(".admin-only").forEach(a => a.hidden = !can("users"));
  $("#addBtn").hidden = !can("edit");
  $("#importCard").hidden = !can("import");
  return d;
}

async function load() {
  const d = await api("parts");
  S.parts = d.parts; S.version = d.version; S.publicUrl = d.public_url || location.origin;
  S.byPn = new Map(S.parts.map(p => [p.pn, p]));
  buildIndex();
  renderAll();
}
function buildIndex() {
  for (const p of S.parts) {
    p._hay = [p.pn, p.name, p.manufacturer, p.model, p.mpn, p.location, p.category, p.positions, p.notes,
      ...(p.specs || []).map(s => s.join(" "))].join(" ").toLowerCase();
    p._ids = [norm(p.pn), norm(p.model), norm(p.mpn)].filter(Boolean);
  }
}
const status = p => {
  const oh = p.on_hand || 0, mn = p.min_qty || 0;
  if (!mn) return oh > 0 ? "ok" : "na";
  return oh <= 0 ? "out" : oh < mn ? "low" : "ok";
};

// ---------- search ----------
function filtered() {
  const q = $("#q").value.trim().toLowerCase();
  const terms = q.split(/\s+/).filter(Boolean);
  const nq = norm(q);
  const low = $("#fLow").checked, noLoc = $("#fNoLoc").checked;
  let out = [];
  for (const p of S.parts) {
    if (S.cat && p.category !== S.cat) continue;
    if (low && !["low", "out"].includes(status(p))) continue;
    if (noLoc && p.location) continue;
    let score = 0;
    if (terms.length) {
      if (nq.length >= 2 && p._ids.some(i => i === nq)) score = 1000;
      else if (nq.length >= 3 && p._ids.some(i => i.startsWith(nq))) score = 500;
      else if (nq.length >= 3 && p._ids.some(i => i.includes(nq))) score = 300;
      if (terms.every(t => p._hay.includes(t))) score += 50;
      if (!score) continue;
    }
    out.push([score, p]);
  }
  const key = $("#sort").value;
  const rank = { out: 0, low: 1, na: 2, ok: 3 };
  const cmp = {
    pn: (a, b) => a.pn.localeCompare(b.pn, undefined, { numeric: true }),
    name: (a, b) => a.name.localeCompare(b.name),
    stock: (a, b) => rank[status(a)] - rank[status(b)] || a.pn.localeCompare(b.pn),
    price: (a, b) => (b.price || 0) - (a.price || 0),
    location: (a, b) => (a.location || "~").localeCompare(b.location || "~", undefined, { numeric: true }),
  }[key];
  out.sort((a, b) => (terms.length ? b[0] - a[0] : 0) || cmp(a[1], b[1]));
  return out.map(x => x[1]);
}
function hl(text, terms) {
  let s = esc(text);
  for (const t of terms) {
    if (t.length < 2) continue;
    s = s.replace(new RegExp("(" + t.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + ")", "ig"), "<mark>$1</mark>");
  }
  return s;
}

// ---------- render ----------
function renderAll() { renderStats(); renderCats(); renderRows(); renderLow(); updateLowBadge(); if (S.view === "labels") renderLabels(); if (S.open) renderDetail(S.open); }

function renderStats() {
  const ps = S.parts;
  const low = ps.filter(p => ["low", "out"].includes(status(p))).length;
  const units = ps.reduce((a, p) => a + (p.on_hand || 0), 0);
  const val = ps.reduce((a, p) => a + (p.on_hand || 0) * (p.price || 0), 0);
  const ordered = ps.reduce((a, p) => a + (p.qty_ordered || 0) * (p.price || 0), 0);
  const noloc = ps.filter(p => !p.location).length;
  $("#stats").innerHTML = `
    <div class="stat"><small>Parts</small><b>${ps.length}</b></div>
    <div class="stat"><small>Units on hand</small><b>${num(units)}</b></div>
    <div class="stat"><small>Stock value</small><b>${eur0(val)}</b></div>
    <div class="stat"><small>Ordered value</small><b>${eur0(ordered)}</b></div>
    <div class="stat click" data-f="low"><small>Below minimum</small><b style="color:var(--${low ? "bad" : "ok"})">${low}</b></div>
    <div class="stat click" data-f="noloc"><small>No location yet</small><b>${noloc}</b></div>`;
}
function renderCats() {
  const counts = {};
  for (const p of S.parts) counts[p.category] = (counts[p.category] || 0) + 1;
  const cats = Object.keys(counts).sort();
  $("#cats").innerHTML = [`<button class="chip ${S.cat ? "" : "on"}" data-cat="">All<em>${S.parts.length}</em></button>`,
    ...cats.map(c => `<button class="chip ${S.cat === c ? "on" : ""}" data-cat="${esc(c)}">${esc(c)}<em>${counts[c]}</em></button>`)].join("");
  const lc = $("#labelCat");
  if (lc.options.length <= 1) lc.innerHTML += cats.map(c => `<option>${esc(c)}</option>`).join("");
}
function stockPill(p) {
  const st = status(p);
  return `<span class="pill ${st}">${num(p.on_hand || 0)}${p.min_qty ? " / " + num(p.min_qty) : ""}</span>`;
}
function renderRows() {
  const list = filtered(); S.list = list;
  const terms = $("#q").value.trim().toLowerCase().split(/\s+/).filter(Boolean);
  const LIMIT = 400;
  $("#rows").innerHTML = list.slice(0, LIMIT).map((p, i) => `
    <tr data-pn="${esc(p.pn)}" class="${i === S.sel ? "sel" : ""}">
      <td class="pn">${hl(p.pn, terms)}</td>
      <td>${hl(p.name, terms)}<span class="cat">${esc(p.category)}</span>${p.positions ? `<div class="sub">${esc(p.positions.split(",").length)} position${p.positions.split(",").length > 1 ? "s" : ""}${p.installed ? " · " + num(p.installed) + " installed" : ""}</div>` : ""}</td>
      <td>${esc(p.manufacturer) || '<span class="sub">—</span>'}<div class="sub pn">${hl(p.model || p.mpn, terms)}</div></td>
      <td>${p.location ? `<span class="loc">${hl(p.location, terms)}</span>` : '<span class="loc none">—</span>'}</td>
      <td class="num">${eur(p.price)}</td>
      <td class="num">${stockPill(p)}</td>
    </tr>`).join("");
  $("#empty").hidden = list.length > 0;
  $("#count").textContent = `${list.length} of ${S.parts.length} parts` + (list.length > LIMIT ? ` · showing first ${LIMIT}` : "");
}
function renderLow() {
  const low = S.parts.filter(p => ["low", "out"].includes(status(p)))
    .sort((a, b) => (b.min_qty - b.on_hand) * (b.price || 0) - (a.min_qty - a.on_hand) * (a.price || 0));
  const total = low.reduce((a, p) => a + (p.min_qty - p.on_hand) * (p.price || 0), 0);
  $("#lowRows").innerHTML = low.map(p => `
    <tr data-pn="${esc(p.pn)}"><td class="pn">${esc(p.pn)}</td><td>${esc(p.name)}<span class="cat">${esc(p.category)}</span></td>
    <td>${p.location ? `<span class="loc">${esc(p.location)}</span>` : '<span class="loc none">—</span>'}</td>
    <td class="num">${stockPill(p)}</td><td class="num">${num(p.min_qty)}</td><td class="num">${num(p.min_qty - p.on_hand)}</td>
    <td class="num">${p.price ? eur((p.min_qty - p.on_hand) * p.price) : "—"}</td></tr>`).join("")
    + (low.length ? `<tr><td colspan="6" class="num"><b>Total to restock (priced parts)</b></td><td class="num"><b>${eur(total)}</b></td></tr>` : `<tr><td colspan="7" class="empty">Everything is at or above minimum.</td></tr>`);
}
function updateLowBadge() {
  const n = S.parts.filter(p => ["low", "out"].includes(status(p))).length;
  $("#lowBadge").textContent = n || "";
}

// ---------- QR ----------
function qrSvg(text, cell = 4) {
  const qr = qrcode(0, "M"); qr.addData(text); qr.make();
  return qr.createSvgTag({ cellSize: cell, margin: 0, scalable: true });
}
const partUrl = p => `${S.publicUrl}/p/${encodeURIComponent(p.pn)}`;

// ---------- detail ----------
async function openPart(pn, push = true) {
  const p = S.byPn.get(pn);
  if (!p) return toast("Unknown part " + pn, true);
  S.open = pn;
  renderDetail(pn);
  $("#drawer").classList.add("open"); $("#drawer").setAttribute("aria-hidden", "false");
  if (push && !location.hash.startsWith("#/p/")) history.replaceState(null, "", "#/p/" + encodeURIComponent(pn));
  try {
    const [full, hist] = await Promise.all([api("parts/" + encodeURIComponent(pn)), api(`parts/${encodeURIComponent(pn)}/movements?limit=30`)]);
    if (S.open !== pn) return;
    p._files = full.files; p._hist = hist;
    renderDetail(pn);
  } catch (e) { console.warn(e); }
}
function closePart() {
  S.open = null;
  $("#drawer").classList.remove("open"); $("#drawer").setAttribute("aria-hidden", "true");
  if (location.hash.startsWith("#/p/")) history.replaceState(null, "", "#/" + (S.view === "parts" ? "" : S.view));
}
function renderDetail(pn) {
  const p = S.byPn.get(pn); if (!p) return closePart();
  const st = status(p), oh = p.on_hand || 0, mn = p.min_qty || 0;
  const pct = mn ? Math.min(100, oh / mn * 100) : (oh ? 100 : 0);
  const color = { ok: "var(--ok)", low: "var(--warn)", out: "var(--bad)", na: "var(--muted)" }[st];
  const positions = (p.positions || "").split(",").map(s => s.trim()).filter(Boolean);
  const links = [...(p._files || []).map(f => ({ ...f, file: true })), ...(p.links || [])];
  $("#detail").innerHTML = `
    <div class="d-head">
      <div><div class="pn">${esc(p.pn)}</div><div class="d-name">${esc(p.name)}</div>
      <div class="sub">${esc(p.category)}${p.manufacturer ? " · " + esc(p.manufacturer) : ""}${p.model ? ` · <span class="pn">${esc(p.model)}</span>` : ""}${p.mpn ? ` · MPN <span class="pn">${esc(p.mpn)}</span>` : ""}</div></div>
      <div style="display:flex;gap:6px">${can("edit") ? '<button class="ghost sm" data-act="edit">Edit</button>' : ""}<button class="ghost sm" data-act="close" title="Esc">✕</button></div>
    </div>
    <div class="stock-card">
      <div class="stock-top">
        <div class="big" style="color:${color}">${num(oh)}<small>${esc(p.unit || "pcs")} on hand${mn ? ` · min ${num(mn)}` : ""}</small></div>
        ${can("adjust") ? `<div class="stepper"><button data-act="dec" title="Take one (−)">−</button><button data-act="inc" title="Add one (+)">+</button></div>` : `<span class="muted">read only</span>`}
      </div>
      <div class="bar"><i style="width:${pct}%;background:${color}"></i></div>
      <div class="actions">
        ${can("adjust") ? `<button class="sm" data-act="take">Take…</button>
        <button class="sm" data-act="return">Return…</button>
        ${p.qty_ordered ? `<button class="sm" data-act="receive">Receive order (+${num(p.qty_ordered)})</button>` : ""}
        <button class="sm" data-act="count">Set count…</button>` : ""}
        ${can("edit_light") ? `<button class="sm" data-act="min">Min…</button>` : ""}
      </div>
    </div>
    <div class="d-sec"><h4>Location</h4>
      ${can("edit_light") ? `<div class="inline-edit"><input id="locInput" value="${esc(p.location)}" placeholder="e.g. A-03-2"><button class="sm" data-act="saveloc">Save</button></div>`
        : `<div>${p.location ? `<span class="loc">${esc(p.location)}</span>` : '<span class="muted">not set</span>'}</div>`}</div>
    <div class="d-sec"><h4>Order & value</h4><dl class="kv">
      <dt>Unit price</dt><dd>${p.price != null ? "€ " + eur(p.price) : "—"}</dd>
      <dt>Stock value</dt><dd>${p.price != null ? "€ " + eur(oh * p.price) : "—"}</dd>
      <dt>Qty ordered</dt><dd>${num(p.qty_ordered) || "—"}${p.qty_on_slip != null ? ` · ${num(p.qty_on_slip)} on packing slip` : ""}</dd>
      <dt>Order / slip</dt><dd>${esc(p.order_no) || "—"} / ${esc(p.packing_slip) || "—"}</dd>
      ${p.installed != null ? `<dt>Installed in line</dt><dd>${num(p.installed)}</dd>` : ""}
    </dl></div>
    ${positions.length ? `<div class="d-sec"><h4>Machine positions (${positions.length})</h4><div class="tags">${positions.map(x => `<span class="tag">${esc(x)}</span>`).join("")}</div></div>` : ""}
    ${p.specs?.length ? `<div class="d-sec"><h4>Specifications</h4><table class="specs">${p.specs.map(([k, v]) => `<tr><td>${esc(k)}</td><td>${esc(v)}</td></tr>`).join("")}</table></div>` : ""}
    <div class="d-sec"><h4>Manuals & documentation</h4><div class="links">
      ${links.map((l, i) => `<a href="${esc(l.url)}" target="_blank" rel="noopener">${l.file ? "📄" : "↗"} ${esc(l.title)}<span>${l.file ? Math.round(l.size / 1024) + " KB" : l.custom ? `<button class="ghost sm danger" data-act="dellink" data-i="${i - (p._files || []).length}">remove</button>` : new URL(l.url).hostname.replace("www.", "")}</span></a>`).join("") || '<p class="muted">No links yet.</p>'}
      ${can("edit_light") ? `<button class="ghost sm" data-act="addlink">＋ Add link</button>` : ""}
      ${can("files") ? `<button class="ghost sm" data-act="upload">⇪ Upload manual / photo</button>` : ""}
    </div></div>
    <div class="d-sec"><h4>Notes</h4>${can("edit_light")
      ? `<textarea id="notesInput" rows="3" style="width:100%" placeholder="Add a note…">${esc(p.notes)}</textarea>
      <button class="sm" data-act="savenotes" style="margin-top:6px">Save notes</button>`
      : `<div class="muted">${esc(p.notes) || "—"}</div>`}</div>
    <div class="d-sec"><h4>QR label</h4><div class="qrbox">${qrSvg(partUrl(p))}<div><div class="sub" style="word-break:break-all">${esc(partUrl(p))}</div>
      <button class="sm" data-act="print1" style="margin-top:8px">Print label</button></div></div></div>
    <div class="d-sec"><h4>History</h4><div class="hist">${p._hist ? (p._hist.length ? p._hist.map(h => `
      <div><span class="d ${h.delta >= 0 ? "pos" : "neg"}">${h.delta > 0 ? "+" : ""}${num(h.delta)}</span><span>→ ${num(h.after)}</span>
      <span class="muted">${esc(h.reason || "")}</span><span class="muted" style="margin-left:auto">${esc(h.user || h.source)} · ${when(h.ts)}</span></div>`).join("") : '<p class="muted">No movements yet.</p>') : '<p class="muted">Loading…</p>'}</div></div>
    ${can("delete") ? `<div class="d-sec"><button class="ghost sm danger" data-act="delete">Delete part</button></div>` : ""}`;
}
function when(ts) {
  const d = new Date(ts * 1000), diff = (Date.now() - d) / 1000;
  if (diff < 60) return "just now";
  if (diff < 3600) return Math.floor(diff / 60) + " min ago";
  if (diff < 86400) return Math.floor(diff / 3600) + " h ago";
  return d.toLocaleDateString("sv-SE") + " " + d.toTimeString().slice(0, 5);
}

async function adjust(pn, body, msg) {
  try {
    const p = await api(`parts/${encodeURIComponent(pn)}/adjust`, { method: "POST", body });
    applyPart(p);
    toast(msg || `${pn}: ${num(p.on_hand)} on hand`);
    refreshHistory(pn);
  } catch (e) { toast(e.message, true); }
}
async function patch(pn, changes, msg) {
  try { const p = await api("parts/" + encodeURIComponent(pn), { method: "PATCH", body: changes }); applyPart(p); toast(msg || "Saved"); }
  catch (e) { toast(e.message, true); }
}
function applyPart(np) {
  const old = S.byPn.get(np.pn);
  if (old) { const keep = { _files: old._files, _hist: old._hist }; Object.keys(old).forEach(k => delete old[k]); Object.assign(old, np, keep); }
  else { S.parts.push(np); S.byPn.set(np.pn, np); }
  buildIndex(); renderAll();
}
async function refreshHistory(pn) {
  const p = S.byPn.get(pn); if (!p || S.open !== pn) return;
  try { p._hist = await api(`parts/${encodeURIComponent(pn)}/movements?limit=30`); renderDetail(pn); } catch {}
}

// ---------- dialogs ----------
function dialog(html, onSubmit) {
  const d = $("#dlg"), f = $("#dlgForm");
  f.innerHTML = html + `<menu><button value="cancel" formnovalidate class="ghost">Cancel</button><button value="ok" class="primary">OK</button></menu>`;
  f.onsubmit = e => {
    if (e.submitter?.value !== "ok") return;
    const data = Object.fromEntries(new FormData(f));
    const r = onSubmit(data);
    if (r === false) e.preventDefault();
  };
  d.showModal();
  setTimeout(() => f.querySelector("input,textarea,select")?.select?.(), 30);
}
function qtyDialog(title, pn, sign, preset = 1) {
  dialog(`<h3>${title} – <span class="pn">${esc(pn)}</span></h3>
    <label>Quantity<input name="n" type="number" min="0" step="any" value="${preset}" required></label>
    <label>Reason<input name="reason" placeholder="${sign < 0 ? "e.g. Replaced on Z040.151" : "e.g. Delivery SLA000418"}"></label>`,
    d => adjust(pn, sign === 0 ? { set: +d.n, reason: d.reason || "Stock count" } : { delta: sign * +d.n, reason: d.reason }));
}
function editDialog(p) {
  const isNew = !p;
  p = p || { pn: "", name: "", category: S.cat || "Mechanical", manufacturer: "", model: "", mpn: "", unit: "PC", price: "", min_qty: 1, location: "", positions: "" };
  const cats = [...new Set(S.parts.map(x => x.category))];
  dialog(`<h3>${isNew ? "New part" : "Edit " + esc(p.pn)}</h3>
    <div class="grid2">
      <label>Part number<input name="pn" value="${esc(p.pn)}" ${isNew ? "required" : "readonly"}></label>
      <label>Category<input name="category" list="catList" value="${esc(p.category)}"><datalist id="catList">${cats.map(c => `<option>${esc(c)}</option>`).join("")}</datalist></label>
    </div>
    <label>Description<input name="name" value="${esc(p.name)}" required></label>
    <div class="grid2">
      <label>Manufacturer<input name="manufacturer" value="${esc(p.manufacturer)}"></label>
      <label>Model / type<input name="model" value="${esc(p.model)}"></label>
      <label>MPN<input name="mpn" value="${esc(p.mpn)}"></label>
      <label>Unit price €<input name="price" type="number" step="any" value="${p.price ?? ""}"></label>
      <label>Minimum stock<input name="min_qty" type="number" step="any" value="${p.min_qty ?? 0}"></label>
      <label>Location<input name="location" value="${esc(p.location)}"></label>
    </div>
    <label>Machine positions (comma separated)<input name="positions" value="${esc(p.positions)}"></label>`,
    d => {
      const body = { ...d, price: d.price === "" ? null : +d.price, min_qty: +d.min_qty || 0 };
      if (isNew) {
        api("parts", { method: "POST", body }).then(np => { applyPart(np); openPart(np.pn); toast("Part created"); }).catch(e => toast(e.message, true));
      } else { delete body.pn; patch(p.pn, body); }
    });
}

// ---------- activity ----------
async function renderActivity() {
  const rows = await api("movements?limit=200");
  $("#activity").innerHTML = rows.map(h => `
    <div data-pn="${esc(h.pn)}"><span class="muted">${when(h.ts)}</span><b class="${h.delta >= 0 ? "pos" : "neg"}">${h.delta > 0 ? "+" : ""}${num(h.delta)}</b>
    <span><span class="pn">${esc(h.pn)}</span> ${esc(h.name || "")} <span class="muted">${h.reason ? "· " + esc(h.reason) : ""}</span></span>
    <span class="muted">${esc(h.user || "—")} · ${esc(h.source)}</span></div>`).join("") || '<p class="muted">No stock movements yet.</p>';
}

// ---------- labels ----------
function renderLabels() {
  const q = $("#labelQ").value.toLowerCase(), cat = $("#labelCat").value;
  const list = S.parts.filter(p => (!cat || p.category === cat) && (!q || p._hay.includes(q)));
  S.labelList = list;
  $("#labelPick").innerHTML = list.map(p => `<label><input type="checkbox" data-pn="${esc(p.pn)}" ${S.labelSel.has(p.pn) ? "checked" : ""}><span><b class="pn">${esc(p.pn)}</b> ${esc(p.name)}</span></label>`).join("");
  $("#labelCount").textContent = S.labelSel.size;
}
function printLabels(pns) {
  const size = $("#labelSize").value;
  $("#printArea").innerHTML = pns.map(pn => S.byPn.get(pn)).filter(Boolean).map(p => `
    <div class="lbl ${size}">${qrSvg(partUrl(p), 3)}<div class="t"><div class="p">${esc(p.pn)}</div><div class="n">${esc(p.name)}</div>
    ${p.location ? `<div class="l">${esc(p.location)}</div>` : ""}<div class="m">${esc([p.manufacturer, p.model || p.mpn].filter(Boolean).join(" "))}</div></div></div>`).join("");
  setTimeout(() => window.print(), 50);
}

// ---------- accounts ----------
async function renderPeople() {
  if (!can("users")) return;
  const users = await api("users");
  const fmt = t => t ? when(t) : "never";
  $("#userRows").innerHTML = users.map(u => `
    <tr data-uid="${u.id}">
      <td class="pn">${esc(u.username)}${u.kind === "device" ? ' <span class="cat">device</span>' : ""}</td>
      <td>${esc(u.name || "")}</td>
      <td style="text-transform:capitalize">${esc(u.role)}</td>
      <td>${u.active ? (u.must_change ? '<span class="state new">must set password</span>' : '<span class="state on">active</span>') : '<span class="state off">disabled</span>'}</td>
      <td>${u.kind === "device" ? (u.has_token ? "token issued" : "no token") : fmt(u.last_login)}</td>
      <td class="rowacts">
        <button class="ghost sm" data-uact="edit">Edit</button>
        ${u.kind === "person" ? '<button class="ghost sm" data-uact="pw">Reset password</button>' : '<button class="ghost sm" data-uact="token">New token</button>'}
        <button class="ghost sm" data-uact="toggle">${u.active ? "Disable" : "Enable"}</button>
        <button class="ghost sm danger" data-uact="del">Delete</button>
      </td></tr>`).join("");
  $("#rolesLegend").innerHTML = S.roles.map(r => `<div><b>${esc(r.id)}</b><br>${esc(r.text)}</div>`).join("");
  $("#peopleNote").textContent = S.canManageUsers
    ? "Everyone who signs in. Roles decide what they may change; every change is recorded in the audit log with their name."
    : "This is the local copy — accounts are managed in the cloud app and shown here read-only.";
  S.users = users;
}

function userDialog(u) {
  const isNew = !u;
  const roles = S.roles.map(r => `<option value="${r.id}" ${u && u.role === r.id ? "selected" : ""}>${esc(r.id)} – ${esc(r.text)}</option>`).join("");
  dialog(`<h3>${isNew ? "New account" : "Edit " + esc(u.username)}</h3>
    ${isNew ? `<label>Username<input name="username" required pattern="[A-Za-z0-9._-]{2,32}" placeholder="e.g. anna"></label>` : ""}
    <label>Full name<input name="name" value="${esc(u ? u.name : "")}" placeholder="Shown in the log"></label>
    <label>Role<select name="role">${roles}</select></label>
    ${isNew ? `<label>Temporary password<input name="password" value="${randomPassword()}" minlength="8" required></label>
      <p class="muted" style="font-size:12px;margin:0">Give them this password; the app asks them to choose their own at first sign-in.</p>` : ""}`,
    d => {
      if (isNew) {
        api("users", { method: "POST", body: { username: d.username, name: d.name, role: d.role, password: d.password } })
          .then(() => { toast(`Account ${d.username} created`); renderPeople(); })
          .catch(e => toast(e.message, true));
      } else {
        api("users/" + u.id, { method: "PATCH", body: { name: d.name, role: d.role } })
          .then(() => { toast("Saved"); renderPeople(); })
          .catch(e => toast(e.message, true));
      }
    });
}

function randomPassword() {
  const w = ["anchor", "copper", "falcon", "granite", "harbor", "lantern", "meadow", "quartz", "summit", "timber", "walnut", "zephyr"];
  const p = n => w[Math.floor(Math.random() * w.length)];
  return `${p()}-${p()}-${100 + Math.floor(Math.random() * 900)}`;
}

function showSecret(title, value, note) {
  dialog(`<h3>${esc(title)}</h3><p class="muted" style="font-size:13px">${esc(note)}</p>
    <div class="tokenbox">${esc(value)}</div>`, () => {});
  navigator.clipboard?.writeText(value).then(() => toast("Copied to clipboard"), () => {});
}

async function renderAudit() {
  if (!can("users")) return;
  S.audit = await api("audit?limit=500");
  drawAudit();
}

function drawAudit() {
  const f = S.auditFilter ?? "changes";
  const keep = r => f === "" ? true
    : f === "changes" ? /^(part|file|import|export|seed|stock)/.test(r.action)
    : f === "signin" ? r.action.startsWith("signin") || r.action === "signout"
    : r.action.startsWith("user.");
  const rows = (S.audit || []).filter(keep);
  $("#auditRows").innerHTML = rows.map(r => `<tr>
    <td class="sub">${when(r.ts)}</td><td class="pn">${esc(r.username || "—")}</td>
    <td>${esc(r.action)}</td><td class="pn">${esc(r.target || "")}</td>
    <td class="sub">${esc(r.detail || "")}${r.ip ? ` <span class="muted">· ${esc(r.ip)}</span>` : ""}</td></tr>`).join("")
    || '<tr><td colspan="5" class="empty">Nothing here.</td></tr>';
}

// ---------- routing ----------
function route() {
  const h = location.hash.replace(/^#\/?/, "");
  let pn = null, view = "parts";
  if (h.startsWith("p/")) pn = decodeURIComponent(h.slice(2));
  else if (["low", "activity", "labels", "settings", "people", "audit"].includes(h)) view = h;
  if (location.pathname.startsWith("/p/")) { pn = decodeURIComponent(location.pathname.slice(3)); history.replaceState(null, "", "/#/p/" + encodeURIComponent(pn)); }
  if (pn) { openPart(pn, false); return; }
  S.view = view;
  $$(".view").forEach(v => v.hidden = v.id !== "view-" + view);
  $$("nav a").forEach(a => a.classList.toggle("active", a.dataset.view === view));
  closePart();
  if (view === "activity") renderActivity();
  if (view === "labels") renderLabels();
  if (view === "settings") renderSettings();
  if (view === "people") renderPeople().catch(e => toast(e.message, true));
  if (view === "audit") renderAudit().catch(e => toast(e.message, true));
}
function renderSettings() {
  const m = S.me || {};
  $("#meInfo").innerHTML = `<dt>Username</dt><dd class="pn">${esc(m.username || "")}</dd>
    <dt>Name</dt><dd>${esc(m.name || "")}</dd>
    <dt>Role</dt><dd style="text-transform:capitalize">${esc(m.role || "")} <span class="muted">– ${esc((S.roles.find(r => r.id === m.role) || {}).text || "")}</span></dd>`;
  $("#serverUrl").textContent = S.publicUrl;
  $("#connectQr").innerHTML = qrSvg(S.publicUrl, 4);
  const k = store.get("apikey", "");
  $("#expCsv").href = "/api/export.csv" + (k ? "?key=" + encodeURIComponent(k) : "");
  $("#expJson").href = "/api/export.json" + (k ? "?key=" + encodeURIComponent(k) : "");
}

// ---------- live updates ----------
function connectLive() {
  const k = store.get("apikey", "");
  const es = new EventSource("/api/events" + (k ? "?key=" + encodeURIComponent(k) : ""));
  const dot = $("#liveDot");
  es.onopen = () => { dot.className = "live on"; dot.querySelector("em").textContent = "Live"; api("sync/status").then(showSync).catch(() => {}); };
  es.onerror = () => { dot.className = "live off"; dot.querySelector("em").textContent = "Reconnecting…"; };
  let pending = null;
  es.onmessage = async ev => {
    const e = JSON.parse(ev.data);
    if (e.type === "syncstatus") return showSync(e);
    if (e.type === "files") { if (S.open) openPart(S.open, false); return; }
    if (e.version && e.version <= S.version) return;
    if (e.type === "stock" && S.byPn.has(e.pn) && e.version === S.version + 1) {
      const p = S.byPn.get(e.pn); p.on_hand = e.after; S.version = e.version;
      renderAll(); if (S.open === e.pn) refreshHistory(e.pn);
      if (e.source !== "web") toast(`${e.pn} ${e.delta > 0 ? "+" : ""}${num(e.delta)} by ${e.user || e.source}`);
      if (S.view === "activity") renderActivity();
      return;
    }
    clearTimeout(pending); pending = setTimeout(load, 150);
  };
}

function showSync(s) {
  const el = $("#syncState");
  if (!s || s.mode !== "replica") { el.hidden = true; return; }
  el.hidden = false;
  const pend = (s.pending ? ` · ${s.pending} to upload` : "") + (s.files_pending ? ` · ${s.files_pending} files` : "");
  el.className = "sync " + (s.online && !s.error ? "ok" : "off");
  el.querySelector("em").textContent = s.online && !s.error ? `Synced with cloud${pend}` : (s.error || "Offline") + pend;
  el.title = `${s.remote}\nLast sync: ${s.last_sync ? new Date(s.last_sync * 1000).toLocaleString("sv-SE") : "never"}\nClick to sync now`;
}

// ---------- events ----------
function bind() {
  $("#q").addEventListener("input", () => { S.sel = S.list.length ? 0 : -1; if (S.view !== "parts") location.hash = "#/"; renderRows(); });
  ["#fLow", "#fNoLoc", "#sort"].forEach(s => $(s).addEventListener("change", () => renderRows()));
  $("#cats").addEventListener("click", e => { const b = e.target.closest(".chip"); if (!b) return; S.cat = b.dataset.cat; renderCats(); renderRows(); });
  $("#stats").addEventListener("click", e => {
    const s = e.target.closest(".stat.click"); if (!s) return;
    if (s.dataset.f === "low") $("#fLow").checked = !$("#fLow").checked; else $("#fNoLoc").checked = !$("#fNoLoc").checked;
    renderRows();
  });
  document.addEventListener("click", e => {
    const tr = e.target.closest("tr[data-pn], .timeline>div[data-pn]");
    if (tr) { S.sel = S.list.findIndex(p => p.pn === tr.dataset.pn); openPart(tr.dataset.pn); }
  });
  $("#detail").addEventListener("click", e => {
    const b = e.target.closest("[data-act]"); if (!b) return;
    const p = S.byPn.get(S.open); if (!p) return;
    const a = b.dataset.act;
    if (b.tagName === "BUTTON" && b.closest("a")) e.preventDefault();
    ({
      close: closePart,
      inc: () => adjust(p.pn, { delta: 1, reason: "Quick add" }),
      dec: () => (p.on_hand || 0) > 0 ? adjust(p.pn, { delta: -1, reason: "Quick take" }) : toast("Nothing in stock", true),
      take: () => qtyDialog("Take from stock", p.pn, -1),
      return: () => qtyDialog("Return to stock", p.pn, 1),
      receive: () => adjust(p.pn, { delta: p.qty_ordered, reason: `Received ${p.order_no || "order"} ${p.packing_slip || ""}`.trim() }, `Received ${num(p.qty_ordered)} × ${p.pn}`),
      count: () => qtyDialog("Set counted quantity", p.pn, 0, p.on_hand || 0),
      min: () => dialog(`<h3>Minimum stock – ${esc(p.pn)}</h3><label>Alert when on hand is below<input name="m" type="number" min="0" step="any" value="${p.min_qty || 0}"></label>`, d => patch(p.pn, { min_qty: +d.m })),
      saveloc: () => patch(p.pn, { location: $("#locInput").value.trim() }, "Location saved"),
      savenotes: () => patch(p.pn, { notes: $("#notesInput").value }, "Notes saved"),
      edit: () => editDialog(p),
      addlink: () => dialog(`<h3>Add documentation link</h3><label>Title<input name="t" required placeholder="Operating instructions"></label><label>URL<input name="u" type="url" required placeholder="https://…"></label>`,
        d => patch(p.pn, { links: [{ title: d.t, url: d.u, custom: true }, ...(p.links || [])] }, "Link added")),
      dellink: () => patch(p.pn, { links: p.links.filter((_, i) => i !== +b.dataset.i) }, "Link removed"),
      print1: () => printLabels([p.pn]),
      upload: () => {
        const inp = document.createElement("input"); inp.type = "file"; inp.multiple = true;
        inp.onchange = async () => {
          for (const f of inp.files) {
            toast(`Uploading ${f.name}…`);
            try { await api(`parts/${encodeURIComponent(p.pn)}/files`, { method: "POST", body: f, headers: { "Content-Type": "application/octet-stream", "X-Filename": encodeURIComponent(f.name) } }); }
            catch (e) { toast(e.message, true); return; }
          }
          toast("Uploaded"); openPart(p.pn, false);
        };
        inp.click();
      },
      delete: () => dialog(`<h3>Delete ${esc(p.pn)}?</h3><p class="muted">This removes the part from the inventory. History is kept.</p>`,
        () => api("parts/" + encodeURIComponent(p.pn), { method: "DELETE" }).then(() => { closePart(); load(); toast("Deleted"); })),
    }[a] || (() => {}))();
  });
  $("#detail").addEventListener("keydown", e => { if (e.key === "Enter" && e.target.id === "locInput") $("[data-act=saveloc]").click(); });
  $("#addBtn").onclick = () => editDialog(null);
  $("#addUser").onclick = () => userDialog(null);
  $("#addDevice").onclick = () => dialog(`<h3>New device token</h3>
      <p class="muted" style="font-size:13px;margin:0 0 12px">For a phone or the site PC. It gets its own token instead of a password; bookings made with it are logged under the person's name when the app sends one.</p>
      <label>Name<input name="username" required pattern="[A-Za-z0-9._-]{2,32}" placeholder="e.g. phone-warehouse"></label>
      <label>Role<select name="role">${S.roles.map(r => `<option value="${r.id}" ${r.id === "staff" ? "selected" : ""}>${r.id} – ${r.text}</option>`).join("")}</select></label>`,
    d => api("users", { method: "POST", body: { username: d.username, name: d.username, role: d.role, kind: "device" } })
      .then(r => { renderPeople(); showSecret("Device token for " + d.username, r.token, "Paste this into the app's API key field. It is shown only now."); })
      .catch(e => toast(e.message, true)));
  $("#auditFilter").addEventListener("click", e => {
    const b = e.target.closest(".chip"); if (!b) return;
    S.auditFilter = b.dataset.af;
    $$("#auditFilter .chip").forEach(c => c.classList.toggle("on", c === b));
    drawAudit();
  });
  $("#userRows").addEventListener("click", e => {
    const b = e.target.closest("[data-uact]"); if (!b) return;
    const uid = +b.closest("tr").dataset.uid;
    const u = (S.users || []).find(x => x.id === uid); if (!u) return;
    const act = b.dataset.uact;
    if (act === "edit") return userDialog(u);
    if (act === "pw") return dialog(`<h3>Reset password for ${esc(u.username)}</h3>
        <label>New temporary password<input name="password" value="${randomPassword()}" minlength="8" required></label>
        <p class="muted" style="font-size:12px;margin:0">They must choose their own password at the next sign-in, and any open session is signed out.</p>`,
      d => api(`users/${uid}/password`, { method: "POST", body: { password: d.password } })
        .then(r => { renderPeople(); showSecret("Temporary password for " + u.username, r.password, "Give this to them in person; it is shown only now."); })
        .catch(e => toast(e.message, true)));
    if (act === "token") return dialog(`<h3>New token for ${esc(u.username)}</h3><p class="muted" style="font-size:13px">The old token stops working straight away.</p>`,
      () => api(`users/${uid}/token`, { method: "POST" })
        .then(r => { renderPeople(); showSecret("Device token for " + u.username, r.token, "Paste this into the app's API key field. It is shown only now."); })
        .catch(e => toast(e.message, true)));
    if (act === "toggle") return api("users/" + uid, { method: "PATCH", body: { active: !u.active } })
      .then(() => { toast(u.active ? "Disabled" : "Enabled"); renderPeople(); }).catch(e => toast(e.message, true));
    if (act === "del") return dialog(`<h3>Delete ${esc(u.username)}?</h3><p class="muted">Their past bookings stay in the history and the audit log.</p>`,
      () => api("users/" + uid, { method: "DELETE" }).then(() => { toast("Deleted"); renderPeople(); }).catch(e => toast(e.message, true)));
  });
  $("#themeBtn").onclick = toggleTheme;
  $("#syncState").onclick = () => api("sync/now", { method: "POST" }).then(() => toast("Syncing…")).catch(e => toast(e.message, true));
  setInterval(() => api("sync/status").then(showSync).catch(() => {}), 15000);
  window.addEventListener("hashchange", route);

  // labels
  $("#labelQ").oninput = renderLabels; $("#labelCat").onchange = renderLabels;
  $("#labelPick").addEventListener("change", e => { const c = e.target; c.checked ? S.labelSel.add(c.dataset.pn) : S.labelSel.delete(c.dataset.pn); $("#labelCount").textContent = S.labelSel.size; });
  $("#labelAll").onclick = () => { S.labelList.forEach(p => S.labelSel.add(p.pn)); renderLabels(); };
  $("#labelNone").onclick = () => { S.labelSel.clear(); renderLabels(); };
  $("#labelPrint").onclick = () => S.labelSel.size ? printLabels([...S.labelSel]) : toast("Select some parts first", true);

  // settings
  $("#savePw").onclick = async () => {
    const cur = $("#pwCur").value, nw = $("#pwNew").value;
    if (nw !== $("#pwNew2").value) return toast("The two new passwords do not match", true);
    if (nw.length < 8) return toast("Password must be at least 8 characters", true);
    try { await api("me", { method: "POST", body: { current: cur, new: nw } }); toast("Password changed"); $("#pwCur").value = $("#pwNew").value = $("#pwNew2").value = ""; }
    catch (e) { toast(e.message, true); }
  };
  $("#importFile").onchange = async e => {
    const f = e.target.files[0]; if (!f) return;
    toast("Importing…");
    try { const r = await api("import", { method: "POST", body: f, headers: { "Content-Type": "application/octet-stream", "X-Filename": f.name } }); toast(`Imported ${r.imported} parts`); load(); }
    catch (err) { toast(err.message, true); }
    e.target.value = "";
  };

  // keyboard
  document.addEventListener("keydown", e => {
    const typing = /INPUT|TEXTAREA|SELECT/.test(document.activeElement.tagName) && document.activeElement.id !== "q";
    if ($("#dlg").open) return;
    if (e.key === "Escape") {
      if (S.open) return closePart();
      if (document.activeElement.id === "q") { $("#q").value = ""; renderRows(); $("#q").blur(); }
      return;
    }
    if (typing) return;
    const inSearch = document.activeElement.id === "q";
    if (e.key === "/" && !inSearch) { e.preventDefault(); $("#q").focus(); $("#q").select(); return; }
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      if (S.view !== "parts" || !S.list.length) return;
      e.preventDefault();
      S.sel = Math.max(0, Math.min(S.list.length - 1, S.sel + (e.key === "ArrowDown" ? 1 : -1)));
      renderRows();
      document.querySelector("tr.sel")?.scrollIntoView({ block: "nearest" });
      if (S.open) openPart(S.list[S.sel].pn);
      return;
    }
    if (e.key === "Enter" && S.list[S.sel] && S.view === "parts") { openPart(S.list[S.sel].pn); $("#q").blur(); return; }
    if (inSearch) return;
    if (S.open && (e.key === "+" || e.key === "=")) return $("[data-act=inc]").click();
    if (S.open && (e.key === "-" || e.key === "_")) return $("[data-act=dec]").click();
    const views = { 1: "#/", 2: "#/low", 3: "#/activity", 4: "#/labels", 5: "#/settings", 6: can("users") ? "#/people" : null, 7: can("users") ? "#/audit" : null };
    if (views[e.key]) location.hash = views[e.key];
    if (e.key.toLowerCase() === "n" && can("edit")) { e.preventDefault(); editDialog(null); }
    if (e.key.toLowerCase() === "t") toggleTheme();
  });
}
function toggleTheme() {
  const cur = document.documentElement.dataset.theme === "light" ? "dark" : "light";
  document.documentElement.dataset.theme = cur; store.set("theme", cur);
}

document.documentElement.dataset.theme = store.get("theme", matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark");
bind();
loadMe().then(load).then(() => { route(); connectLive(); }).catch(e => { if (e.message !== "auth") toast("Could not load: " + e.message, true); route(); });
})();
