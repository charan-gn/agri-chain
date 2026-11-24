const state = {
  view: "dashboard",
  batches: [],
  prices: [],
  offers: [],
  details: new Map(),
  selectedId: null,
  actors: new Map(),
  actorRequests: new Map(),
  refreshing: false,
  ledgerUrl: "",
  updatedAt: 0,
};

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function shortId(value) {
  if (!value) return "unknown";
  const [role, id] = String(value).split(":");
  return id ? `${role}:${id.slice(0, 6)}` : String(value).slice(0, 12);
}

function actorName(id) {
  const cached = state.actors.get(id);
  return cached ? cached.name || shortId(id) : shortId(id);
}

function formatKg(value) {
  return Number(value || 0).toLocaleString("en-IN") + " kg";
}

function formatPrice(paise) {
  if (paise === null || paise === undefined) return "—";
  return "₹" + (Number(paise) / 100).toLocaleString("en-IN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}

function formatTime(ts) {
  if (!ts) return "—";
  return new Date(Number(ts)).toLocaleString("en-IN", {
    day: "2-digit",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function setStatus(ok, text) {
  const dot = $("#ledger-dot");
  dot.classList.toggle("ok", ok);
  dot.classList.toggle("bad", !ok);
  $("#ledger-status").textContent = text;
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { Accept: "application/json", ...(options.headers || {}) },
    ...options,
  });
  let payload = {};
  try {
    payload = await response.json();
  } catch (_) {
    payload = {};
  }
  if (!response.ok) {
    throw new Error(payload.detail || payload.error || `HTTP ${response.status}`);
  }
  return payload;
}

function normalizeBatchInput(raw) {
  const value = String(raw || "").trim();
  if (!value) return "";
  const match = value.match(/agri:\/\/batch\/([0-9a-fA-F-]{36})/);
  if (match) return match[1];
  return value.replace(/^agri:\/\/batch\//, "").trim();
}

async function hydrateActors(root) {
  const ids = new Set();
  $$("[data-actor-id]", root).forEach((node) => {
    const id = node.dataset.actorId;
    if (!id) return;
    if (state.actors.has(id)) {
      node.textContent = actorName(id);
    }
    ids.add(id);
  });
  await Promise.all(
    Array.from(ids)
      .filter((id) => !state.actors.has(id) && !state.actorRequests.has(id))
      .map(async (id) => {
        const request = api("/api/actors/" + encodeURIComponent(id))
          .then((actor) => {
            state.actors.set(id, actor);
            return actor;
          })
          .catch(() => null)
          .finally(() => state.actorRequests.delete(id));
        state.actorRequests.set(id, request);
        await request;
      })
  );
  $$("[data-actor-id]", root).forEach((node) => {
    const id = node.dataset.actorId;
    if (id && state.actors.has(id)) node.textContent = actorName(id);
  });
}

function renderKpis() {
  const events = Array.from(state.details.values()).reduce(
    (total, detail) => total + (detail.events?.length || 0),
    0
  );
  const tamper = state.batches.reduce((total, batch) => total + (batch.tamperCount || 0), 0);
  const openOffers = state.offers.filter((offer) => offer.status === "open").length;
  const wheat = state.prices.find((row) => row.crop === "wheat" && row.market === "warangal")
    || state.prices[0];

  $("#kpi-batches").textContent = state.batches.length || "0";
  $("#kpi-events").textContent = events || "0";
  $("#kpi-tamper").textContent = tamper || "0";
  $("#kpi-offers").textContent = openOffers || "0";
  $("#kpi-price").textContent = wheat ? formatPrice(wheat.pricePerKgPaise) : "—";
}

function renderBatchRows() {
  const tbody = $("#batch-rows");
  $("#batch-count").textContent = `${state.batches.length} tracked`;

  if (!state.batches.length) {
    tbody.innerHTML = '<tr><td colspan="5" class="empty">No batches registered yet. Run the demo seed or register one in the app.</td></tr>';
    return;
  }

  tbody.innerHTML = state.batches
    .map((batch) => {
      const tampered = Number(batch.tamperCount || 0);
      return `
        <tr data-batch="${escapeHtml(batch.batchId)}" class="${batch.batchId === state.selectedId ? "selected" : ""}">
          <td><span class="crop">${escapeHtml(batch.crop)}</span><span class="grade">${escapeHtml(batch.grade)}</span></td>
          <td><span data-actor-id="${escapeHtml(batch.ownerId)}">${escapeHtml(shortId(batch.ownerId))}</span></td>
          <td>${formatKg(batch.initialKg)}</td>
          <td><span class="badge ${tampered ? "bad" : "ok"}">${tampered ? tampered + " flag" + (tampered === 1 ? "" : "s") : "clear"}</span></td>
          <td><span class="badge ${batch.verified ? "ok" : "bad"}">${batch.verified ? "verified" : "broken"}</span></td>
        </tr>`;
    })
    .join("");

  hydrateActors(tbody);
}

function renderPrices() {
  const container = $("#price-list");
  if (!state.prices.length) {
    container.innerHTML = '<p class="empty">No price snapshots.</p>';
    return;
  }
  container.innerHTML = state.prices
    .map(
      (row) => `
        <div class="price-row">
          <div><span class="crop">${escapeHtml(row.crop)}</span><span class="market">${escapeHtml(row.market)}</span></div>
          <strong>${formatPrice(row.pricePerKgPaise)}<span class="muted">/kg</span></strong>
        </div>`
    )
    .join("");
}

function renderOffers() {
  const container = $("#offer-list");
  $("#offer-count").textContent = `${state.offers.length} offer${state.offers.length === 1 ? "" : "s"}`;
  if (!state.offers.length) {
    container.innerHTML = '<p class="empty">No barter offers yet.</p>';
    return;
  }
  container.innerHTML = state.offers
    .map((offer) => {
      const tone = offer.status === "open" ? "warn" : offer.status === "accepted" ? "ok" : "neutral";
      return `
        <div class="offer">
          <div class="offer-top">
            <span class="badge ${tone}">${escapeHtml(offer.status)}</span>
            <span class="muted">${formatTime(offer.ts)}</span>
          </div>
          <p>
            <span data-actor-id="${escapeHtml(offer.actorId)}">${escapeHtml(shortId(offer.actorId))}</span>
            gives <strong>${formatKg(offer.giveKg)}</strong>
            for <strong>${formatKg(offer.wantKg)}</strong>
            from <span data-actor-id="${escapeHtml(offer.withActorId)}">${escapeHtml(shortId(offer.withActorId))}</span>
          </p>
          <p><a href="/trace?batch=${encodeURIComponent(offer.batchId)}" class="mono">${escapeHtml(shortId(offer.batchId))}</a></p>
        </div>`;
    })
    .join("");
  hydrateActors(container);
}

function routeSvg(events) {
  const fixes = events
    .filter((event) => event.type === "GPS" && event.lat !== null && event.lon !== null)
    .map((event) => ({
      lat: Number(event.lat),
      lon: Number(event.lon),
      ts: Number(event.ts),
      tamper: false,
    }));

  if (!fixes.length) {
    return '<div class="empty">No GPS fixes were recorded for this batch.</div>';
  }
  if (fixes.length === 1) {
    return `
      <svg class="route-svg" viewBox="0 0 640 230" role="img" aria-label="Single GPS fix">
        <circle class="point start" cx="320" cy="115" r="8"></circle>
        <text x="338" y="119">${escapeHtml(fixes[0].lat.toFixed(6))}, ${escapeHtml(fixes[0].lon.toFixed(6))}</text>
      </svg>`;
  }

  events
    .filter((event) => event.type === "TAMPER")
    .forEach((tamper) => {
      let closest = fixes[0];
      for (const fix of fixes) {
        if (fix.ts <= Number(tamper.ts)) closest = fix;
      }
      closest.tamper = true;
    });

  const width = 640;
  const height = 230;
  const pad = 26;
  const lats = fixes.map((fix) => fix.lat);
  const lons = fixes.map((fix) => fix.lon);
  const minLat = Math.min(...lats);
  const maxLat = Math.max(...lats);
  const minLon = Math.min(...lons);
  const maxLon = Math.max(...lons);
  const latSpan = maxLat - minLat || 0.0001;
  const lonSpan = maxLon - minLon || 0.0001;

  const points = fixes.map((fix) => {
    const x = pad + ((fix.lon - minLon) / lonSpan) * (width - pad * 2);
    const y = height - pad - ((fix.lat - minLat) / latSpan) * (height - pad * 2);
    return { ...fix, x: Number(x.toFixed(1)), y: Number(y.toFixed(1)) };
  });

  const line = points.map((point) => `${point.x},${point.y}`).join(" ");
  const start = points[0];
  const end = points[points.length - 1];
  const markers = points
    .map((point, index) => {
      if (point.tamper) {
        return `<g><circle class="tamper" cx="${point.x}" cy="${point.y}" r="7"></circle>
          <path d="M${point.x - 3} ${point.y - 3}L${point.x + 3} ${point.y + 3}M${point.x + 3} ${point.y - 3}L${point.x - 3} ${point.y + 3}" stroke="#fff" stroke-width="1.6"></path></g>`;
      }
      if (index === 0 || index === points.length - 1) return "";
      return `<circle class="point" cx="${point.x}" cy="${point.y}" r="2.6"></circle>`;
    })
    .join("");

  return `
    <svg class="route-svg" viewBox="0 0 ${width} ${height}" role="img"
         aria-label="GPS route with ${points.length} fixes and ${points.filter((point) => point.tamper).length} tamper flags">
      <polyline class="route-line" points="${line}"></polyline>
      ${markers}
      <circle class="point start" cx="${start.x}" cy="${start.y}" r="6"></circle>
      <circle class="point end" cx="${end.x}" cy="${end.y}" r="6"></circle>
      <text x="${Math.min(Math.max(start.x + 9, 8), width - 60)}" y="${Math.max(start.y - 9, 14)}">start</text>
      <text x="${Math.min(Math.max(end.x + 9, 8), width - 50)}" y="${Math.max(end.y - 9, 14)}">latest</text>
    </svg>`;
}

function renderTimeline(events) {
  return events
    .slice()
    .reverse()
    .map((event) => {
      const tamper = event.type === "TAMPER";
      const where = event.lat && event.lon ? ` · ${event.lat}, ${event.lon}` : "";
      return `
        <li class="${tamper ? "tamper" : ""}">
          <div class="event-title">
            <strong>${escapeHtml(event.type)}</strong>
            <span class="badge ${tamper ? "bad" : "neutral"}">${formatTime(event.ts)}</span>
          </div>
          <div class="event-meta">
            <span data-actor-id="${escapeHtml(event.actorId)}">${escapeHtml(shortId(event.actorId))}</span>${escapeHtml(where)}
          </div>
          <div class="event-data">${escapeHtml(JSON.stringify(event.data))}</div>
        </li>`;
    })
    .join("");
}

function renderDetail(batch, target) {
  const template = $("#batch-detail-template");
  const fragment = template.content.cloneNode(true);
  const field = (name) => $('[data-field="' + name + '"]', fragment);

  field("batch-id").textContent = batch.batchId;
  field("title").textContent = `${batch.crop} · grade ${batch.grade}`;
  field("subtitle").textContent = `${formatKg(batch.initialKg)} registered · ${batch.events.length} events`;
  const verified = field("verified");
  verified.innerHTML = `
    <span class="badge ${batch.verified ? "ok" : "bad"}">${batch.verified ? "chain verified" : "chain broken"}</span>
    ${batch.tamperCount ? `<span class="badge bad">${batch.tamperCount} tamper</span>` : '<span class="badge ok">no tamper</span>'}`;

  const banner = field("tamper-banner");
  if (batch.tamperCount) {
    banner.hidden = false;
    banner.textContent = `${batch.tamperCount} tamper flag${batch.tamperCount === 1 ? "" : "s"} on this batch. Check the marked points and the timeline.`;
  }

  field("initial").textContent = formatKg(batch.initialKg);
  field("owner").innerHTML = `<span data-actor-id="${escapeHtml(batch.ownerId)}">${escapeHtml(shortId(batch.ownerId))}</span>`;
  field("balances").innerHTML = Object.entries(batch.balances || {})
    .map(([actor, kg]) => `<div><span data-actor-id="${escapeHtml(actor)}">${escapeHtml(shortId(actor))}</span>: ${formatKg(kg)}</div>`)
    .join("") || "—";
  field("hash").textContent = String(batch.lastHash || "").slice(0, 14) + "…";
  field("hash").title = batch.lastHash || "";
  const gpsCount = batch.events.filter((event) => event.type === "GPS").length;
  field("route-note").textContent = `${gpsCount} GPS fix${gpsCount === 1 ? "" : "es"}`;
  field("route").innerHTML = routeSvg(batch.events);
  field("event-count").textContent = `${batch.events.length} events`;
  field("timeline").innerHTML = renderTimeline(batch.events);

  target.replaceChildren(fragment);
  hydrateActors(target);
}

function selectBatch(batchId, { trace = false } = {}) {
  const detail = state.details.get(batchId);
  state.selectedId = batchId;
  renderBatchRows();
  if (!detail) return;
  if (trace) {
    $("#trace-detail").innerHTML = "";
    renderDetail(detail, $("#trace-detail"));
  } else {
    renderDetail(detail, $("#dashboard-detail"));
  }
}

async function loadBatch(batchId, { trace = false, scroll = false } = {}) {
  const target = trace ? $("#trace-detail") : $("#dashboard-detail");
  target.innerHTML = '<div class="empty tall">Loading batch…</div>';
  const detail = await api("/api/batches/" + encodeURIComponent(batchId));
  state.details.set(batchId, detail);
  state.selectedId = batchId;
  renderBatchRows();
  if (trace) {
    renderDetail(detail, target);
    if (scroll) target.scrollIntoView({ behavior: "smooth", block: "start" });
  } else {
    renderDetail(detail, target);
  }
  return detail;
}

async function refreshAll({ quiet = false } = {}) {
  if (state.refreshing) return;
  state.refreshing = true;
  if (!quiet) setStatus(false, "Refreshing…");
  try {
    const [config, batchPayload, pricePayload, offerPayload] = await Promise.all([
      state.ledgerUrl ? Promise.resolve({ ledgerUrl: state.ledgerUrl }) : api("/api/config"),
      api("/api/batches"),
      api("/api/prices"),
      api("/api/offers"),
    ]);

    state.ledgerUrl = config.ledgerUrl;
    state.batches = batchPayload.batches || [];
    state.prices = pricePayload.prices || [];
    state.offers = offerPayload.offers || [];
    state.updatedAt = Date.now();

    await Promise.all(
      state.batches.map(async (batch) => {
        const cached = state.details.get(batch.batchId);
        if (cached && cached.lastHash === batch.lastHash) return;
        try {
          state.details.set(batch.batchId, await api("/api/batches/" + encodeURIComponent(batch.batchId)));
        } catch (_) {
          if (!cached) state.details.delete(batch.batchId);
        }
      })
    );

    if (!state.selectedId || !state.batches.some((batch) => batch.batchId === state.selectedId)) {
      state.selectedId = state.batches[0]?.batchId || null;
    }

    renderKpis();
    renderBatchRows();
    renderPrices();
    renderOffers();

    if (state.selectedId) {
      const detail = state.details.get(state.selectedId);
      if (detail) renderDetail(detail, $("#dashboard-detail"));
    } else {
      $("#dashboard-detail").innerHTML = '<div class="empty tall">Select a batch to inspect its route and history.</div>';
    }

    setStatus(true, "Ledger live");
    $("#footer-ledger").textContent = "Ledger: " + state.ledgerUrl;
    $("#footer-updated").textContent = "Updated: " + new Date(state.updatedAt).toLocaleTimeString("en-IN");
  } catch (error) {
    setStatus(false, "Ledger offline");
    if (!quiet) {
      $("#batch-rows").innerHTML = `<tr><td colspan="5" class="empty">${escapeHtml(error.message)}</td></tr>`;
    }
  } finally {
    state.refreshing = false;
  }
}

function setView(view) {
  state.view = view;
  $$(".tab").forEach((tab) => tab.classList.toggle("is-active", tab.dataset.view === view));
  $("#view-dashboard").hidden = view !== "dashboard";
  $("#view-trace").hidden = view !== "trace";
  if (view === "trace") {
    const batchId = new URLSearchParams(location.search).get("batch");
    if (batchId) {
      $("#trace-input").value = "agri://batch/" + normalizeBatchInput(batchId);
      loadBatch(normalizeBatchInput(batchId), { trace: true }).catch(showTraceError);
    }
  }
}

function showTraceError(error) {
  const box = $("#trace-error");
  box.hidden = false;
  box.textContent = error.message || String(error);
}

function bindEvents() {
  $$(".tab").forEach((tab) => tab.addEventListener("click", () => setView(tab.dataset.view)));

  $("#refresh").addEventListener("click", () => refreshAll());
  $("#refresh-prices").addEventListener("click", async (event) => {
    const button = event.currentTarget;
    button.disabled = true;
    try {
      await api("/api/prices/refresh", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: "{}",
      });
      await refreshAll({ quiet: true });
    } catch (error) {
      setStatus(false, error.message);
    } finally {
      button.disabled = false;
    }
  });

  $("#batch-rows").addEventListener("click", (event) => {
    const row = event.target.closest("tr[data-batch]");
    if (!row) return;
    selectBatch(row.dataset.batch);
  });

  $("#trace-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    $("#trace-error").hidden = true;
    const batchId = normalizeBatchInput($("#trace-input").value);
    if (!batchId) {
      showTraceError(new Error("Enter a batch id or scan payload."));
      return;
    }
    history.replaceState(null, "", "/trace?batch=" + encodeURIComponent(batchId));
    try {
      await loadBatch(batchId, { trace: true, scroll: true });
    } catch (error) {
      showTraceError(error);
    }
  });

  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible" && state.view === "dashboard") refreshAll({ quiet: true });
  });
}

bindEvents();
setView(new URLSearchParams(location.search).has("batch") ? "trace" : "dashboard");
refreshAll();
setInterval(() => {
  if (document.visibilityState === "visible" && state.view === "dashboard") refreshAll({ quiet: true });
}, 10000);
