"use strict";

const $ = (id) => document.getElementById(id);
const icons = {
  grid: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
  layers: '<path d="m3 7 9-5 9 5-9 5zM3 12l9 5 9-5M3 17l9 5 9-5"/>',
  book: '<path d="M12 5c-3-2-7-2-10-1v15c3-1 7-1 10 1 3-2 7-2 10-1V4c-3-1-7-1-10 1v15"/>',
  external: '<path d="M14 4h6v6m0-6-9 9m8 1v6H4V5h6"/>',
  panel: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M9 4v16"/>',
  chevron: '<path d="m8 10 4 4 4-4"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 6v6l4 2"/>',
  arrow: '<path d="M4 12h15m-5-5 5 5-5 5"/>',
  right: '<path d="m9 6 6 6-6 6"/>',
  split: '<path d="M5 5v5c0 4 8 2 8 7v3M5 10c0 3 14 1 14 7v3"/>',
  search: '<circle cx="10" cy="10" r="6"/><path d="m15 15 6 6"/>',
  x: '<path d="m6 6 12 12M18 6 6 18"/>',
  inbox: '<path d="M3 15l3-10h12l3 10v5H3zM3 15h5l2 3h4l2-3h5"/>',
  check: '<circle cx="12" cy="12" r="9"/><path d="m8 12 3 3 6-7"/>',
  alert: '<path d="m12 3 10 17H2zM12 9v4m0 3h.01"/>',
  activity: '<path d="M2 12h5l3-9 4 18 3-9h5"/>',
  code: '<path d="m8 7-5 5 5 5m8-10 5 5-5 5m-3-13-2 16"/>',
};
function icon(name) {
  return (
    '<i aria-hidden="true"><svg viewBox="0 0 24 24">' +
    icons[name] +
    "</svg></i>"
  );
}
document.querySelectorAll("[data-icon]").forEach((el) => {
  el.innerHTML = icon(el.dataset.icon).replace(/^<i[^>]*>|<\/i>$/g, "");
  el.setAttribute("aria-hidden", "true");
});
const esc = (value) =>
  String(value ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const label = (word) => word.charAt(0) + word.slice(1).toLowerCase();
const categoryNames = {
  FINANCIAL: "Financial",
  SYSTEM_LOG: "System log",
  IOT_TELEMETRY: "IoT telemetry",
  UNKNOWN: "Unknown",
};
const colors = ["#82aa61", "#43694e", "#c4ac70", "#a2aaa2"];
const sampleTime = Date.now();
const examples = [
  [
    "invoice.received",
    "ledger-api",
    "FINANCIAL",
    "Invoice INV-2048 received for USD 480.00.",
    "invoice",
    "INV-2048",
  ],
  [
    "sensor.reading",
    "edge-gateway",
    "IOT_TELEMETRY",
    "Temperature reading from sensor T-19 is 23.4°C.",
    "sensor",
    "T-19",
  ],
  [
    "deploy.completed",
    "infra-agent",
    "SYSTEM_LOG",
    "Deployment for the orders service completed.",
    "service",
    "orders",
  ],
  [
    "payment.settled",
    "billing-hook",
    "FINANCIAL",
    "Payment PAY-824 settled for USD 75.",
    "payment",
    "PAY-824",
  ],
  [
    "log.warning",
    "infra-agent",
    "SYSTEM_LOG",
    "Cache hit rate on host web-03 fell below target.",
    "host",
    "web-03",
  ],
  [
    "document.received",
    "doc-upload",
    "UNKNOWN",
    "Maintenance note for site North.",
    "site",
    "North",
  ],
];
const samples = Array.from({ length: 48 }, (_, i) => {
  const [eventType, sourceId, category, rawPayload, k, v] = examples[i % 6];
  const status =
    i === 1
      ? "PROCESSING"
      : i === 3
        ? "RECEIVED"
        : i === 12
          ? "FAILED"
          : "COMPLETED";
  const createdAt = new Date(
    sampleTime - (Math.pow(i / 48, 1.22) * 58 + 0.2) * 60000,
  ).toISOString();
  return {
    id: "a56b17d8-a5f4-4000-9000-" + String(7349 + i).padStart(12, "0"),
    sourceId,
    eventType,
    rawPayload,
    metadata: {},
    createdAt,
    updatedAt: createdAt,
    status,
    errorMessage:
      status === "FAILED"
        ? "Processing retries exhausted. Inspect the dead-letter topic."
        : null,
    extractedData:
      status === "COMPLETED"
        ? {
            summary: rawPayload,
            category,
            risk_level: eventType === "log.warning" ? "MEDIUM" : "LOW",
            sentiment_score: eventType === "log.warning" ? -0.2 : 0,
            actionable_entities: [{ key: k, value: v }],
          }
        : null,
  };
});
let events = samples,
  key = "",
  parser = "",
  publicWorkspace = false,
  parserModel = "",
  activeId = null,
  nextAction = null,
  detailUpdated = null,
  feedRequest = 0;
const previewOnly = document.body.hasAttribute("data-preview-only");
const stream = () => location.hash === "#stream";
const localServiceKey = "omni-local-development";
const isLoopback = ["localhost", "127.0.0.1", "[::1]"].includes(location.hostname);
const providerNames = {groq: "Groq Cloud", local: "Local rules", openai: "OpenAI"};

const fmtTime = (stamp) =>
  new Date(stamp).toLocaleTimeString([], {
    hour: "2-digit",
    minute: "2-digit",
  });
function notice(message = "") {
  $("notice").textContent = message;
  $("notice").hidden = !message;
}
function badge(status) {
  return (
    '<span class="status ' +
    esc(status.toLowerCase()) +
    '">' +
    esc(label(status)) +
    "</span>"
  );
}
function render() {
  const help = location.hash === "#help";
  const tab = location.hash === "#ai" ? "ai" : help ? "help" : stream() ? "stream" : "overview";
  $("aiStudio").hidden = tab !== "ai";
  $("help").hidden = !help;
  $("overview").hidden = tab !== "overview";
  $("feed").hidden = help || tab === "ai";
  $("helpMode").textContent = previewOnly
    ? "This public preview uses sample events. Live ingestion is available in a connected server deployment."
    : key
    ? "This workspace is connected. Active parser: " + (providerNames[parser] || parser) + "."
    : "You are viewing sample data. The Windows console connects automatically. Hosted workspaces use the service API key."; 
  document.querySelectorAll("[data-nav]").forEach((n) => {
    const selected = n.dataset.nav === tab;
    n.classList.toggle("active", selected);
    if (selected) n.setAttribute("aria-current", "page");
    else n.removeAttribute("aria-current");
  });
  $("crumb").textContent = {ai:"AI workspace",help:"Help",stream:"Event stream",overview:"Overview"}[tab];
  $("feedTitle").textContent = stream() ? "Event stream" : "Recent events";
  $("viewAll").hidden = stream();
  $("filters").hidden = !stream();
  $("streamIngest").hidden = !stream();
  $("feedSubtitle").hidden = !stream();
  $("scope").textContent = events.length;
  const n = events.length,
    completed = events.filter((e) => e.status === "COMPLETED").length,
    failed = events.filter((e) => e.status === "FAILED").length;
  const stats = [
    ["Total received", n, "Events in view", "inbox", "#e8efdF", "#4d6845"],
    ["Completed", completed, "Analysis stored", "check", "#e7f2dd", "#527b36"],
    [
      "In flight",
      n - completed - failed,
      "Queued or processing",
      "activity",
      "#fbf2dd",
      "#91702b",
    ],
    ["Failed", failed, "Retries exhausted", "alert", "#f9eae5", "#a65d4a"],
  ];
  $("stats").innerHTML = stats
    .map(
      ([name, num, detail, ic, bg, fg]) =>
        '<article class="stat" style="--bg:' +
        bg +
        ";--fg:" +
        fg +
        '"><div class="stat-top">' +
        name +
        icon(ic) +
        "</div><strong>" +
        num +
        "</strong><small>" +
        detail +
        "</small></article>",
    )
    .join("");
  drawChart();
  const counts = Object.keys(categoryNames).map(
    (c) =>
      events.filter((e) => (e.category || e.extractedData?.category) === c)
        .length,
  );
  $("categories").innerHTML = counts
    .map(
      (v, i) =>
        '<div class="mix-row"><span>' +
        Object.values(categoryNames)[i] +
        '</span><div class="track"><span style="background:' +
        colors[i] +
        ";width:" +
        (100 * v) / Math.max(completed, 1) +
        '%"></span></div><b>' +
        v +
        "</b></div>",
    )
    .join("");
  renderRows();
  $("modeName").textContent = publicWorkspace ? "Live workspace" : key ? "Connected API" : "Sample workspace";
  $("connection").classList.toggle("live", !!key);
  $("footerMode").textContent = key
    ? "Connected · " +
      ({ groq: "Groq structured parser", openai: "OpenAI structured parser", local: "Local rules parser" }[parser] || "Unknown parser")
    : "Sample data";

  document.dispatchEvent(new Event("workspace:updated"));
  if (tab === "overview") requestAnimationFrame(wave);
}
function drawChart() {
  const end = key ? Date.now() : sampleTime,
    duration = Number($("period").value) * 60000,
    start = end - duration;
  const bins = Array(24).fill(0);
  for (const e of events) {
    const index = Math.floor(
      ((new Date(e.createdAt) - start) / duration) * bins.length,
    );
    if (index >= 0 && index < bins.length) bins[index]++;
  }
  const max = Math.max(1, ...bins);
  $("chart").innerHTML =
    '<div class="hist" role="img" aria-label="' +
    bins.reduce((a, b) => a + b, 0) +
    ' events across 24 time intervals">' +
    bins
      .map(
        (v, i) =>
          '<div title="' +
          fmtTime(start + (i * duration) / 24) +
          " · " +
          v +
          ' events" style="height:' +
          (v / max) * 96 +
          '%"></div>',
      )
      .join("") +
    '</div><div class="axis">' +
    [0, 0.25, 0.5, 0.75, 1]
      .map((x) => "<span>" + fmtTime(start + duration * x) + "</span>")
      .join("") +
    "</div>";
}
function renderRows() {
  const q = $("search").value.toLowerCase(),
    status = $("status").value;
  const rows = stream()
    ? events.filter(
        (e) =>
          (!status || e.status === status) &&
          [e.id, e.sourceId, e.eventType].some((v) =>
            v.toLowerCase().includes(q),
          ),
      )
    : events.slice(0, 5);
  $("rows").innerHTML = rows
    .map(
      (e) =>
        '<tr data-id="' +
        esc(e.id) +
        '"><td><button><span class="event-icon">' +
        icon("code") +
        "</span>" +
        esc(e.eventType) +
        '</button></td><td class="source">' +
        esc(e.sourceId) +
        "</td><td>" +
        badge(e.status) +
        '</td><td class="time">' +
        esc(fmtTime(e.createdAt)) +
        '</td><td class="go">' +
        icon("right") +
        "</td></tr>",
    )
    .join("");
  $("empty").hidden = rows.length > 0;
  $("empty").textContent = events.length
    ? "No events match these filters."
    : "No events yet. Ingest an event to see it here.";
}
function wave() {
  const canvas = $("wave"),
    w = canvas.clientWidth,
    h = canvas.clientHeight;
  if (!w || !h) return;
  const d = Math.min(devicePixelRatio || 1, 2);
  canvas.width = w * d;
  canvas.height = h * d;
  const c = canvas.getContext("2d");
  c.scale(d, d);
  const glow = c.createRadialGradient(
    w * 0.78,
    h * 0.55,
    0,
    w * 0.78,
    h * 0.55,
    w * 0.6,
  );
  glow.addColorStop(0, "#344f2a");
  glow.addColorStop(1, "#172d22");
  c.fillStyle = glow;
  c.fillRect(0, 0, w, h);
  c.fillStyle = "rgba(186,238,152,.17)";
  for (let x = w * 0.36; x < w; x += 15)
    for (let y = 7; y < h; y += 15) c.fillRect(x, y, 1, 1);
  for (let i = 0; i < 63; i++) {
    const t = i / 62;
    c.beginPath();
    for (let x = w * 0.32; x <= w + 2; x += 2) {
      const u = (x - w * 0.32) / (w * 0.68),
        a = u * 7 + t * 3.5;
      const y =
        h *
        (0.67 +
          0.2 * Math.sin(a + 0.7) +
          0.16 * Math.sin(u * 11 - t * 3.2) +
          0.27 * (t - 0.5) +
          0.1 * Math.cos(u * 3 + t * 6));
      if (x === w * 0.32) c.moveTo(x, y);
      else c.lineTo(x, y);
    }
    const g = c.createLinearGradient(w * 0.32, 0, w, 0);
    g.addColorStop(0, "rgba(170,238,90,0)");
    g.addColorStop(0.35, "rgba(169,224,110,.28)");
    g.addColorStop(0.65, "rgba(198,249,123,.85)");
    g.addColorStop(1, "rgba(162,216,105,.53)");
    c.strokeStyle = g;
    c.lineWidth = 0.85;
    c.stroke();
  }
}
function modal(title, body) {
  $("dialogTitle").textContent = title;
  $("dialogBody").innerHTML = body;
  if (!$("dialog").open) $("dialog").showModal();
}
function closeDialog() {
  $("dialog").close();
}
$("close").onclick = closeDialog;
$("dialog").addEventListener("close", () => {
  activeId = null;
  nextAction = null;
});
function showDetail(e) {
  activeId = e.id;
  detailUpdated = e.updatedAt;
  const a = e.extractedData;
  modal(
    "Event details",
    '<section class="detail"><div class="detail-top"><strong>' +
      esc(e.eventType) +
      "</strong>" +
      badge(e.status) +
      '</div><div class="detail-id">' +
      esc(e.id) +
      "</div><dl><dt>Source</dt><dd>" +
      esc(e.sourceId) +
      "</dd><dt>Received</dt><dd>" +
      esc(new Date(e.createdAt).toLocaleString()) +
      "</dd></dl><h3>Raw payload</h3><pre>" +
      esc(e.rawPayload) +
      "</pre>" +
      (a
        ? "<h3>Analysis</h3><p>" +
          esc(a.summary) +
          "</p><dl><dt>Category</dt><dd>" +
          esc(categoryNames[a.category]) +
          "</dd><dt>Risk level</dt><dd>" +
          esc(label(a.risk_level)) +
          "</dd><dt>Sentiment</dt><dd>" +
          esc(a.sentiment_score) +
          "</dd></dl>" +
          (a.actionable_entities?.length
            ? "<h3>Extracted entities</h3>" +
              a.actionable_entities
                .map(
                  (x) =>
                    '<p class="entity"><b>' +
                    esc(x.key) +
                    "</b> · " +
                    esc(x.value) +
                    "</p>",
                )
                .join("")
            : "")
        : "<h3>Processing status</h3><p>" +
          esc(
            e.errorMessage || "Analysis will appear when processing completes.",
          ) +
          "</p>") +
      (Object.keys(e.metadata || {}).length
        ? "<h3>Metadata</h3><pre>" +
          esc(JSON.stringify(e.metadata, null, 2)) +
          "</pre>"
        : "") +
      "</section>",
  );
}
async function api(url, options = {}, apiKey = key) {
  const r = await fetch(url, {
    ...options,
    headers: { "X-API-KEY": apiKey, ...options.headers },
  });
  if (!r.ok) {
    let p = await r.json().catch(() => ({}));
    throw Error(p.detail || p.title || "HTTP " + r.status);
  }
  return r.json();
}
async function refresh() {
  const requestedKey = key,
    sequence = ++feedRequest;
  if (!requestedKey) return;
  try {
    const feed = await api("/api/v1/events");
    if (key !== requestedKey || sequence !== feedRequest) return;
    events = feed.events;
    parser = feed.parserMode;
    parserModel = feed.parserModel || "";
    notice();
    render();
    const changed = events.find(
      (e) => e.id === activeId && e.updatedAt !== detailUpdated,
    );
    if (changed) await inspect(changed);
  } catch (e) {
    if (key === requestedKey) notice("Refresh failed. " + e.message);
  }
}
function connect(action = null) {
  nextAction = action;
  modal(
    "Connect to your pipeline",
    '<form id="connectForm"><p>Enter the API key configured on this service. The key stays in memory for this tab.</p><label class="field">API key<input type="password" id="keyInput" autocomplete="off" required></label><div class="error" role="alert"></div><div class="dialog-actions">' +
      (key
        ? '<button type="button" class="secondary" id="disconnect">Disconnect</button>'
        : "") +
      '<button class="accent">Connect API</button></div></form>',
  );
  const disconnect = $("disconnect");
  if (disconnect)
    disconnect.onclick = () => {
      key = "";
      events = samples;
      parser = "";
      notice();
      render();
      closeDialog();
    };
  $("connectForm").onsubmit = async (e) => {
    e.preventDefault();
    const form = e.currentTarget,
      button = form.querySelector(".accent");
    button.disabled = true;
    try {
      const supplied = $("keyInput").value;
      const feed = await api("/api/v1/events", {}, supplied);
      key = supplied;
      events = feed.events;
      parser = feed.parserMode;
    parserModel = feed.parserModel || "";
      notice();
      render();
      const action = nextAction;
      nextAction = null;
      if (action) action();
      else closeDialog();
    } catch (err) {
      form.querySelector(".error").textContent = err.message;
    } finally {
      button.disabled = false;
    }
  };
}
async function intake(example = false) {
  await initialConnection;
  if (!key) {
    connect(() => intake(example));
    return;
  }
  modal(
    "Ingest event",
    '<form id="ingestForm"><label class="field">Source ID<input name="sourceId" placeholder="ledger-api" maxlength="100" required></label><label class="field">Event type<input name="eventType" placeholder="invoice.received" maxlength="100" required></label><label class="field">Raw payload<textarea id="payload" name="rawPayload" placeholder="Paste document text or webhook JSON" maxlength="65536" required></textarea></label><label class="field">Load a text file<input id="file" type="file" accept=".txt,.log,.json,.csv,.md,text/plain"></label><div class="error" role="alert"></div><div class="dialog-actions"><button class="accent">Submit event</button></div></form>',
  );
  const form = $("ingestForm"),
    error = form.querySelector(".error");
  if (example) {
    form.elements.sourceId.value = "ledger-api";
    form.elements.eventType.value = "invoice.received";
    form.elements.rawPayload.value = $("examplePayload").textContent;
  }
  $("file").onchange = async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    if (file.size > 262144) {
      error.textContent = "Choose a file of up to 65,536 characters.";
      return;
    }
    try {
      const content = await file.text();
      if (content.length > 65536)
        throw Error("Payload exceeds 65,536 characters.");
      $("payload").value = content;
      error.textContent = "";
    } catch (err) {
      error.textContent = err.message;
    }
  };
  form.onsubmit = async (e) => {
    e.preventDefault();
    const button = form.querySelector("button");
    button.disabled = true;
    try {
      const accepted = await api("/api/v1/events/ingest", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(Object.fromEntries(new FormData(form))),
      });
      const row = await api("/api/v1/events/" + accepted.eventId);
      await refresh();
      showDetail(row);
    } catch (err) {
      error.textContent = err.message;
    } finally {
      button.disabled = false;
    }
  };
}
$("connection").onclick = async () => {
  await initialConnection;
  if (publicWorkspace || (isLoopback && key === localServiceKey)) {
    modal(publicWorkspace ? "Live workspace" : "Local workspace", "<p>Connected to the service. Active parser: " +
      esc(providerNames[parser] || parser) + ".</p>");
  } else connect();
};
document.querySelectorAll(".ingest").forEach((button) => button.onclick = () => intake());
$("tryInvoice").onclick = () => intake(true);
async function inspect(row) {
  activeId = row.id;
  if (!key) {
    showDetail(row);
    return;
  }
  const requestedKey = key;
  try {
    const detail = await api("/api/v1/events/" + row.id);
    if (key === requestedKey && activeId === row.id) showDetail(detail);
  } catch (err) {
    notice("Could not load event details. " + err.message);
  }
}
$("rows").onclick = (e) => {
  const row = e.target.closest("tr");
  if (row) {
    let found = events.find((x) => x.id === row.dataset.id);
    if (found) inspect(found);
  }
};
$("period").onchange = drawChart;
$("search").oninput = renderRows;
$("status").onchange = renderRows;
window.addEventListener("hashchange", render);
window.addEventListener("resize", wave);
setInterval(() => {
  if (!document.hidden && key) refresh();
}, 4000);
async function connectLoopback() {
  if (previewOnly) return;
  try {
    const config = await fetch("/workspace-config", {signal: AbortSignal.timeout(5000)});
    if (config.ok) publicWorkspace = (await config.json()).publicWorkspace === true;
  } catch {}
  if (!isLoopback && !publicWorkspace) return;
  try {
    const feed = await api("/api/v1/events", {signal: AbortSignal.timeout(5000)}, publicWorkspace ? "public-workspace" : localServiceKey);
    key = publicWorkspace ? "public-workspace" : localServiceKey;
    events = feed.events;
    parser = feed.parserMode;
    parserModel = feed.parserModel || "";
    render();
  } catch {
    // A localhost deployment with a custom service key can use Connect API.
  }
}
if (previewOnly) {
  const example = samples[0];
  const inspectExample = () => showDetail(example);
  $("connection").disabled = true;
  $("connection").querySelector('[data-icon="chevron"]').hidden = true;
  $("docs").hidden = true;
  document.querySelectorAll(".ingest").forEach(button => {
    button.textContent = "Inspect a sample event";
    button.onclick = inspectExample;
  });
  $("tryInvoice").textContent = "Inspect sample invoice";
  $("tryInvoice").onclick = inspectExample;
  const guide = document.querySelector(".guide-example");
  guide.querySelector("h2").textContent = "Explore an invoice";
  guide.querySelector("p").textContent = "Open this sample to compare its raw payload with the extracted fields.";
  $("examplePayload").textContent = example.rawPayload;
  document.querySelector(".guide-intro p").textContent = "Explore the event stream and read a sample analysis.";
  $("helpMode").parentElement.querySelectorAll("details").forEach(detail => detail.hidden = true);
  $("footerMode").textContent = "Sample data";
}
const initialConnection = connectLoopback();
render();
