'use strict';

const FEEDS = ['messages', 'listings', 'orders', 'trades', 'prices'];
let currentFeed = 'messages';
let dictionary = { crops: [], regions: [] };

function esc(value) {
  return String(value ?? '').replace(/[&<>"']/g, (ch) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  })[ch]);
}

async function api(path) {
  const response = await fetch(path, { headers: { Accept: 'application/json' } });
  const data = await response.json();
  if (!response.ok) throw new Error(data.detail || data.error || response.statusText);
  return data;
}

function cropName(id) {
  const crop = dictionary.crops.find((item) => Number(item.id) === Number(id));
  return crop ? crop.label || crop.name : `#${id}`;
}

function regionName(id) {
  const region = dictionary.regions.find((item) => Number(item.id) === Number(id));
  return region ? region.label || region.name : `#${id}`;
}

function money(paise) {
  const value = Number(paise || 0);
  return `₹${(value / 100).toFixed(2)}`;
}

function shortId(value) {
  const text = String(value || '');
  return text.length > 10 ? `${text.slice(0, 8)}…` : text;
}

function table(columns, rows) {
  if (!rows.length) return '<p class="empty">No records yet.</p>';
  const head = columns.map(([label]) => `<th>${esc(label)}</th>`).join('');
  const body = rows.map((cells) => `<tr>${cells.map((cell) => `<td>${esc(cell)}</td>`).join('')}</tr>`).join('');
  return `<table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>`;
}

function renderStats(stats) {
  const cards = [
    ['Messages', stats.messages],
    ['Listings', stats.listings],
    ['Orders', stats.orders],
    ['Trades', stats.trades],
    ['Outbox', stats.outbox],
    ['Checkpoint', stats.checkpointSeq ?? stats.checkpoint],
  ];
  document.getElementById('stats').innerHTML = cards.map(([label, value]) =>
    `<div class="stat"><strong>${esc(value ?? 0)}</strong><span>${esc(label)}</span></div>`
  ).join('');
}

function renderFeed(name, data) {
  const rows = data[name] || data.items || [];
  if (name === 'messages') {
    document.getElementById('feed').innerHTML = table(
      [['Seq', 'seq'], ['Type', 'type'], ['Status', 'status'], ['MsgId', 'msgId'], ['From', 'fromId'], ['Via', 'via']],
      rows.map((item) => [item.seq, item.type, item.status, shortId(item.msgId), shortId(item.fromId), item.via])
    );
  } else if (name === 'listings') {
    document.getElementById('feed').innerHTML = table(
      [['Listing', 'listingId'], ['Crop', 'crop'], ['Available', 'remainingKg'], ['Ask', 'askPaise'], ['Region', 'region']],
      rows.map((item) => [shortId(item.listingId), cropName(item.crop), `${item.remainingKg} kg`, money(item.askPaise), regionName(item.region)])
    );
  } else if (name === 'orders') {
    document.getElementById('feed').innerHTML = table(
      [['Order', 'orderId'], ['Status', 'status'], ['Qty', 'qtyKg'], ['Bid', 'bidPaise'], ['Buyer', 'buyerId']],
      rows.map((item) => [shortId(item.orderId), item.status, `${item.qtyKg} kg`, money(item.bidPaise), shortId(item.buyerId)])
    );
  } else if (name === 'trades') {
    document.getElementById('feed').innerHTML = table(
      [['Trade', 'orderId'], ['Crop', 'crop'], ['Qty', 'qtyKg'], ['Price', 'pricePaise'], ['Region', 'region']],
      rows.map((item) => [shortId(item.orderId), item.cropName || cropName(item.crop), `${item.qtyKg} kg`, money(item.pricePaise), regionName(item.region)])
    );
  } else {
    const latest = Math.max(0, ...rows.map((item) => Number(item.version || 0)));
    const latestRows = rows.filter((item) => Number(item.version) === latest).flatMap((item) => item.rows || []);
    document.getElementById('feed').innerHTML = table(
      [['Crop', 'crop'], ['Price', 'pricePaise'], ['Trades', 'trades'], ['Version', 'version']],
      latestRows.map((item) => [cropName(item.crop), money(item.pricePaise), item.trades, latest])
    );
  }
}

async function loadMandi() {
  const meta = document.getElementById('mandiMeta');
  const body = document.getElementById('mandiBody');
  try {
    const data = await api('/api/mandi');
    const rows = (data.quotes || []).map((item) => [
      item.commodity || '—',
      item.market || '—',
      item.date || data.observedDate || '—',
      money(item.modalPaisePerKg),
      `${money(item.minPaisePerKg)} / ${money(item.maxPaisePerKg)}`,
      item.source || data.source || '—'
    ]);
    body.innerHTML = table(
      [['Crop', 'crop'], ['Market', 'market'], ['Date', 'date'],
       ['Modal', 'modalPaisePerKg'], ['Min / Max', 'min-max'], ['Source', 'source']],
      rows
    );
    const parts = [`Source: ${esc(data.source || 'unknown')}`];
    if (data.observedDate) parts.push(`observed ${esc(data.observedDate)}`);
    parts.push(esc(data.disclaimer || 'Advisory reference data.'));
    if (data.error) parts.push(esc(data.error));
    meta.innerHTML = parts.join(' · ');
  } catch (error) {
    meta.textContent = 'Mandi reference unavailable.';
    body.innerHTML = `<p class="error">${esc(error.message)}</p>`;
  }
}

async function refresh() {
  const feed = document.getElementById('feed');
  try {
    const [info, stats, data] = await Promise.all([
      api('/api/ledger'),
      api('/api/stats'),
      api(`/api/${currentFeed}`),
      loadMandi()
    ]);
    dictionary = { crops: info.crops || [], regions: info.regions || [] };
    document.getElementById('ledger').textContent = `ledger ${shortId(info.ledgerId)} · seq ${stats.messages}`;
    renderStats(stats);
    renderFeed(currentFeed, data);
  } catch (error) {
    feed.innerHTML = `<p class="error">${esc(error.message)}</p>`;
  }
}

document.querySelectorAll('.tab').forEach((button) => {
  button.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach((tab) => tab.classList.remove('active'));
    button.classList.add('active');
    currentFeed = button.dataset.feed;
    refresh();
  });
});

document.getElementById('refresh').addEventListener('click', refresh);
document.getElementById('mandiRefresh').addEventListener('click', loadMandi);
refresh();
window.setInterval(refresh, 15000);
