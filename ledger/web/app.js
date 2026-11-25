"use strict";

const $ = (selector) => document.querySelector(selector);

const TYPE_CLASS = {
  REGISTER: "ok",
  TRANSFER: "warn",
  TAMPER: "bad",
  DELIVER: "info",
  GPS: "info",
  PRICE: "violet",
  BARTER: "violet",
};

const state = {
  auto: true,
  timer: null,
  selected: null,
  batches: [],
};

function esc(value) {
  return String(value ?? "").replace(/[&<>"']/g, (char) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;",
  }[char]));
}

function short(value, length = 10) {
  const text = String(value ?? "");
  return text.length <= length ? text : `${text.slice(0, length)}…`;
}

function actor(value) {
  const text = String(value ?? "—");
  const split = text.indexOf(":");
  if (split < 0) return short(text, 14);
  return `${text.slice(0, split)}:${text.slice(split + 1, split + 5)}`;
}

function fmtInt(value) {
  return Number(value ?? 0).toLocaleString("en-IN");
}

function fmtKg(value) {
  return `${fmtInt(value)} kg`;
}

function fmtPaise(value) {
  return `₹${(Number(value ?? 0) / 100).toLocaleString("en-IN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;
}

function fmtTime(ts) {
  if (!ts) return "—";
  return new Date(ts).toLocaleString("en-IN", {
    day: "2-digit",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
  });
}

async function get(path) {
  const response = await fetch(path, {
    cache: "no-store",
    headers: { Accept: "application/json" },
  });
  if (!response.ok) throw new Error(`${path} returned ${response.status}`);
  return response.json();
}

function setHealth(ok) {
  const pill = $("#health");
  pill.className = `pill ${ok ? "ok" : "bad"}`;
  pill.textContent = ok ? "ledger online" : "offline";
}

function showBanner(message) {
  const banner = $("#banner");
  banner.textContent = message;
  banner.classList.remove("hidden");
}

function hideBanner() {
  $("#banner").classList.add("hidden");
}

function renderStats(stats) {
  $("#statBatches").textContent = fmtInt(stats.batches);
  $("#statEvents").textContent = fmtInt(stats.events);
  $("#statActors").textContent = fmtInt(stats.actors);
  $("#statTamper").textContent = fmtInt(stats.tamperEvents);

  const total = Number(stats.batches) || 0;
  const valid = Number(stats.validChains) || 0;
  const chain = $("#statChain");
  const note = $("#statChainNote");
  if (total === 0) {
    chain.textContent = "—";
    chain.style.color = "";
    note.textContent = "waiting for batches";
  } else if (valid === total) {
    chain.textContent = "100%";
    chain.style.color = "#9ee37d";
    note.textContent = `all ${total} batch chain(s) verify`;
  } else {
    chain.textContent = `${valid}/${total}`;
    chain.style.color = "#ff7b72";
    note.textContent = "a chain failed verification";
  }
}

function renderBatches(batches) {
  const tbody = $("#batchTable tbody");
  tbody.innerHTML = batches.map((batch) => `
    <tr data-id="${esc(batch.batchId)}" tabindex="0">
      <td><strong>${esc(batch.crop)}</strong><span class="sub">grade ${esc(batch.grade)}</span></td>
      <td><code>${esc(short(batch.batchId, 8))}</code></td>
      <td>${esc(actor(batch.ownerId))}<span class="sub">${fmtInt(Object.keys(batch.balances || {}).length)} holder(s)</span></td>
      <td class="num">${fmtKg(batch.initialKg)}</td>
      <td class="num">${batch.tamperCount
        ? `<span class="badge bad">${fmtInt(batch.tamperCount)}</span>`
        : '<span class="badge ok">0</span>'}</td>
      <td><span class="badge ${batch.verified ? "ok" : "bad"}">${batch.verified ? "valid" : "broken"}</span></td>
    </tr>`).join("");
  $("#batchCount").textContent = `${batches.length} batch${batches.length === 1 ? "" : "es"}`;
  $("#batchEmpty").classList.toggle("hidden", batches.length > 0);
}

function renderFeed(events) {
  $("#eventFeed").innerHTML = events.map((event) => `
    <li>
      <span class="badge ${TYPE_CLASS[event.type] || "muted"}">${esc(event.type)}</span>
      <span class="event-main">
        <strong>${esc(event.crop || (event.batchId === "market" ? "market" : "batch"))}</strong>
        <span>${esc(actor(event.actorId))} · ${esc(short(event.batchId, 12))}</span>
      </span>
      <time datetime="${esc(new Date(event.ts).toISOString())}">${fmtTime(event.ts)}</time>
    </li>`).join("");
  $("#feedEmpty").classList.toggle("hidden", events.length > 0);
}

function renderOffers(offers) {
  const byBatch = new Map(state.batches.map((batch) => [batch.batchId, batch]));
  const label = (batchId) => {
    const batch = byBatch.get(batchId);
    return batch ? `${batch.crop} ${short(batchId, 6)}` : short(batchId, 8);
  };
  const statusClass = { open: "warn", accepted: "ok", canceled: "muted", cancelled: "muted" };

  $("#offerTable tbody").innerHTML = offers.map((offer) => `
    <tr>
      <td><code>${esc(short(offer.offerId, 8))}</code><span class="sub">${esc(actor(offer.actorId))}</span></td>
      <td>${fmtKg(offer.giveKg)}<span class="sub">${esc(label(offer.batchId))}</span></td>
      <td>${fmtKg(offer.wantKg)}<span class="sub">${esc(label(offer.wantBatchId))}</span></td>
      <td><span class="badge ${statusClass[offer.status] || "muted"}">${esc(offer.status)}</span></td>
    </tr>`).join("");
  $("#offerEmpty").classList.toggle("hidden", offers.length > 0);
}

function renderPrices(prices) {
  const groups = new Map();
  for (const price of prices) {
    if (!groups.has(price.crop)) groups.set(price.crop, []);
    groups.get(price.crop).push(price);
  }
  $("#priceGrid").innerHTML = [...groups.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([crop, rows]) => `
      <article class="price-card">
        <h3>${esc(crop)}</h3>
        ${rows.map((price) => `
          <div class="price-row">
            <span class="market">${esc(price.market)}</span>
            <b>${fmtPaise(price.pricePerKgPaise)}/kg</b>
          </div>`).join("")}
      </article>`).join("");
  $("#priceEmpty").classList.toggle("hidden", prices.length > 0);
  $("#priceUpdated").textContent = prices.length
    ? `updated ${fmtTime(Math.max(...prices.map((price) => price.ts)))}`
    : "";
}

function dataValue(key, value) {
  if (value === null || value === undefined) return "—";
  if (key === "weightKg" || key === "giveKg" || key === "wantKg") return fmtKg(value);
  if (key === "pricePerKgPaise") return `${fmtPaise(value)}/kg`;
  if (key.endsWith("ActorId")) return actor(value);
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

function renderDetail(batch) {
  $("#detailTitle").innerHTML =
    `${esc(batch.crop)} · grade ${esc(batch.grade)} ` +
    `<span class="badge ${batch.verified ? "ok" : "bad"}">${batch.verified ? "verified" : "broken chain"}</span>`;

  const balances = Object.entries(batch.balances || {}).sort((a, b) => b[1] - a[1]);
  const maxBalance = Math.max(1, ...balances.map(([, kg]) => Number(kg)));
  const events = batch.events || [];

  $("#detailBody").innerHTML = `
    <div class="detail-grid">
      <div class="meta"><span>Batch id</span><strong title="${esc(batch.batchId)}">${esc(short(batch.batchId, 14))}</strong></div>
      <div class="meta"><span>Owner</span><strong title="${esc(batch.ownerId)}">${esc(actor(batch.ownerId))}</strong></div>
      <div class="meta"><span>Initial weight</span><strong>${fmtKg(batch.initialKg)}</strong></div>
      <div class="meta"><span>Tamper flags</span><strong>${fmtInt(batch.tamperCount)}</strong></div>
      <div class="meta"><span>Last hash</span><strong class="mono" title="${esc(batch.lastHash)}">${esc(short(batch.lastHash, 16))}</strong></div>
      <div class="meta"><span>Events</span><strong>${fmtInt(events.length)}</strong></div>
    </div>

    <div class="qr-line">
      <span class="muted">QR payload</span>
      <code>agri://batch/${esc(batch.batchId)}</code>
      <button class="button ghost" type="button" data-copy="agri://batch/${esc(batch.batchId)}">Copy</button>
    </div>

    <h3>Balances</h3>
    <div class="balances">
      ${balances.length ? balances.map(([who, kg]) => `
        <div class="balance">
          <span class="who" title="${esc(who)}">${esc(actor(who))}</span>
          <b>${fmtKg(kg)}</b>
          <span class="bar"><i style="width:${Math.max(2, (Number(kg) / maxBalance) * 100)}%"></i></span>
        </div>`).join("") : '<p class="empty">No token holders yet.</p>'}
    </div>

    <h3>Chain</h3>
    <ol class="timeline">
      ${events.map((event) => {
        const data = Object.entries(event.data || {});
        return `
        <li>
          <div class="row">
            <span class="badge ${TYPE_CLASS[event.type] || "muted"}">${esc(event.type)}</span>
            <strong>${esc(actor(event.actorId))}</strong>
            <time>${fmtTime(event.ts)}</time>
          </div>
          ${event.lat && event.lon ? `<p class="mono">${esc(event.lat)}, ${esc(event.lon)}</p>` : ""}
          <div class="kv">
            ${data.map(([key, value]) => `<span><b>${esc(key)}</b> ${esc(dataValue(key, value))}</span>`).join("")}
          </div>
          <details>
            <summary class="mono">hash ${esc(short(event.hash, 18))} · prev ${esc(short(event.prevHash, 12) || "genesis")}</summary>
            <pre>${esc(JSON.stringify(event, null, 2))}</pre>
          </details>
        </li>`;
      }).join("")}
    </ol>`;
}

async function loadSelected() {
  if (!state.selected) return;
  try {
    const batch = await get(`/batches/${encodeURIComponent(state.selected)}`);
    if (state.selected === batch.batchId) renderDetail(batch);
  } catch (error) {
    $("#detailBody").innerHTML = `<p class="empty">Could not load this batch: ${esc(error.message)}</p>`;
  }
}

function selectBatch(batchId, keepHash = false) {
  state.selected = batchId;
  $("#batchDetail").classList.remove("hidden");
  $("#detailTitle").textContent = "Loading batch…";
  $("#detailBody").innerHTML = "";
  if (!keepHash) history.replaceState(null, "", `#batch=${batchId}`);
  loadSelected();
}

function closeDetail() {
  state.selected = null;
  $("#batchDetail").classList.add("hidden");
  history.replaceState(null, "", location.pathname);
}

function readHash() {
  const match = location.hash.match(/batch=([0-9a-fA-F-]{36})/);
  return match ? match[1] : null;
}

async function refresh() {
  const button = $("#refreshButton");
  button.disabled = true;
  try {
    const [stats, batchBody, offerBody, priceBody] = await Promise.all([
      get("/stats?limit=14"),
      get("/batches"),
      get("/barter/offers"),
      get("/prices"),
    ]);
    state.batches = batchBody.batches || [];
    renderStats(stats);
    renderBatches(state.batches);
    renderFeed(stats.recent || []);
    renderOffers(offerBody.offers || []);
    renderPrices(priceBody.prices || []);
    setHealth(true);
    hideBanner();
    await loadSelected();
  } catch (error) {
    setHealth(false);
    showBanner(`Cannot reach the ledger API: ${error.message}. Start it with ./tools/ledger.sh`);
  } finally {
    button.disabled = false;
    $("#baseUrl").textContent =
      `${location.origin} · checked ${new Date().toLocaleTimeString("en-IN")}`;
  }
}

function setAuto(enabled) {
  state.auto = enabled;
  if (state.timer) clearInterval(state.timer);
  state.timer = enabled ? setInterval(refresh, 5000) : null;
}

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    const field = document.createElement("textarea");
    field.value = text;
    field.style.position = "fixed";
    field.style.opacity = "0";
    document.body.appendChild(field);
    field.select();
    const copied = document.execCommand("copy");
    field.remove();
    return copied;
  }
}

document.addEventListener("DOMContentLoaded", () => {
  $("#refreshButton").addEventListener("click", refresh);
  $("#autoRefresh").addEventListener("change", (event) => setAuto(event.target.checked));
  $("#closeDetail").addEventListener("click", closeDetail);

  $("#batchTable").addEventListener("click", (event) => {
    const row = event.target.closest("tr[data-id]");
    if (row) selectBatch(row.dataset.id);
  });
  $("#batchTable").addEventListener("keydown", (event) => {
    const row = event.target.closest("tr[data-id]");
    if (row && (event.key === "Enter" || event.key === " ")) {
      event.preventDefault();
      selectBatch(row.dataset.id);
    }
  });

  document.addEventListener("click", async (event) => {
    const button = event.target.closest("[data-copy]");
    if (!button) return;
    const copied = await copyText(button.dataset.copy);
    if (!copied) return;
    const previous = button.textContent;
    button.textContent = "Copied";
    setTimeout(() => { button.textContent = previous; }, 1200);
  });

  window.addEventListener("hashchange", () => {
    const batchId = readHash();
    if (batchId) selectBatch(batchId, true);
    else closeDetail();
  });

  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible" && state.auto) refresh();
  });

  const initial = readHash();
  if (initial) selectBatch(initial, true);
  setAuto($("#autoRefresh").checked);
  refresh();
});
