"use strict";

(() => {
  const examples = {
    invoice: ["invoice.received", "Invoice INV-42 for USD 85.00 from Northwind is overdue. Contact accounts@northwind.example to arrange payment."],
    incident: ["incident.reported", "Critical outage. Service: payments-api. Host: prod-03. Region: eu-west. Error rate reached 38% after deployment v2.8. Roll back the release and notify the on-call engineer."],
    telemetry: ["sensor.alert", "Sensor: T-19. Device: cold-room-2. Temperature: 12.4 C. Expected range: 2-8 C. Warning: temperature exceeded the safe threshold for 18 minutes."]
  };
  let current = null;
  let polling = false;
  let submitting = false;
  let revision = 0;
  let timer = null;
  let startedAt = 0;
  const payload = $("studioPayload");
  const submit = $("analyzeButton");
  const stages = ["stageReceived", "stageAnalysis", "stageSaved"];

  function provider() {
    const name = providerNames[parser] || "Connect to analyze";
    $("studioProvider").textContent = previewOnly ? "Sample analysis" : name + (parserModel && parserModel !== "rules" ? " · " + parserModel : "");
  }

  function error(message = "") {
    $("studioError").textContent = message;
    $("studioError").hidden = !message;
  }

  function loadExample(name) {
    const example = examples[name];
    $("studioType").value = example[0];
    payload.value = example[1];
    $("studioCount").textContent = payload.value.length.toLocaleString() + " / 65,536";
    document.querySelectorAll("[data-studio-example]").forEach(button => {
      button.setAttribute("aria-pressed", String(button.dataset.studioExample === name));
    });
  }

  function setBusy(busy) {
    polling = busy;
    submit.disabled = busy;
    submit.textContent = busy ? "Analysis in progress…" : "Analyze payload ↗";
    $("studioFile").disabled = busy;
    document.querySelectorAll("[data-studio-example]").forEach(button => button.disabled = busy);
  }

  function stopTimer() {
    clearInterval(timer);
    timer = null;
  }

  function paint(event) {
    current = event;
    const status = event.status;
    const completed = status === "COMPLETED";
    const failed = status === "FAILED";
    stages.forEach(id => $(id).className = "");
    $("stageReceived").className = "done";
    if (status === "RECEIVED") $("stageAnalysis").className = "active";
    if (status === "PROCESSING") $("stageAnalysis").className = "active";
    if (completed) {
      $("stageAnalysis").className = "done";
      $("stageSaved").className = "done";
    }
    if (failed) $("stageAnalysis").className = "failed";
    const messages = {
      RECEIVED: "Event saved. Waiting for an analysis worker.",
      PROCESSING: parser === "local" ? "Local parser is extracting fields." : "AI is extracting the summary, category, risk, and actionable entities.",
      COMPLETED: "Analysis complete. The structured result is saved to this event.",
      FAILED: event.errorMessage || "Analysis failed after its retries."
    };
    $("studioStatus").textContent = messages[status] || status;
    $("analysisWaiting").hidden = true;
    $("analysisLoading").hidden = completed || failed;
    $("analysisResult").hidden = !completed;
    $("studioEventId").textContent = event.id;
    $("studioInspect").hidden = false;
    if (!completed && !failed) return;
    stopTimer();
    setBusy(false);
    $("studioCheck").hidden = true;
    if (failed) { error(messages.FAILED); return; }
    const result = event.extractedData;
    const seconds = Math.max(0, (new Date(event.updatedAt) - new Date(event.createdAt)) / 1000);
    $("studioTiming").textContent = seconds.toFixed(1) + "s end-to-end";
    $("analysisTags").replaceChildren();
    const tags = [categoryNames[result.category] || result.category, result.risk_level + " risk", providerNames[parser] || "Sample"];
    tags.forEach((text, index) => {
      const tag = document.createElement("span");
      tag.textContent = text;
      if (index === 1) tag.className = "risk-" + result.risk_level.toLowerCase();
      $("analysisTags").append(tag);
    });
    $("analysisSummary").textContent = result.summary;
    const sentiment = Number(result.sentiment_score);
    $("analysisSentiment").textContent = (sentiment > 0 ? "+" : "") + sentiment.toFixed(2) + " / " +
      (sentiment > 0.1 ? "Positive" : sentiment < -0.1 ? "Negative" : "Neutral");
    $("analysisEntities").replaceChildren();
    for (const entity of result.actionable_entities || []) {
      const term = document.createElement("dt"), value = document.createElement("dd");
      term.textContent = entity.key;
      value.textContent = entity.value;
      $("analysisEntities").append(term, value);
    }
    if (!result.actionable_entities?.length) {
      const value = document.createElement("dd");
      value.textContent = "No actionable entities found in this payload.";
      $("analysisEntities").append(value);
    }
    $("analysisJson").textContent = JSON.stringify(result, null, 2);
  }

  async function watch(id) {
    if (polling) return;
    setBusy(true);
    $("studioCheck").hidden = true;
    stopTimer();
    startedAt = Date.now();
    timer = setInterval(() => {
      $("studioTiming").textContent = ((Date.now() - startedAt) / 1000).toFixed(1) + "s waiting";
    }, 250);
    try {
      while (polling) {
        const row = await api("/api/v1/events/" + id, { signal: AbortSignal.timeout(15000) });
        paint(row);
        if (["COMPLETED", "FAILED"].includes(row.status)) {
          refresh();
          return;
        }
        await new Promise(resolve => setTimeout(resolve, publicWorkspace ? 2000 : 1000));
      }
    } catch (failure) {
      stopTimer();
      setBusy(false);
      error("Could not refresh this event. " + failure.message + " Your accepted event is saved. Check its status to continue.");
      $("studioCheck").hidden = false;
      $("analysisLoading").hidden = true;
      $("studioTiming").textContent = "Status unavailable";
    }
  }

  $("studioForm").addEventListener("submit", async event => {
    event.preventDefault();
    if (polling || submitting) return;
    revision++;
    await initialConnection;
    error();
    if (previewOnly) { paint(samples[0]); return; }
    if (!key) { connect(() => submit.click()); return; }
    submitting = true;
    submit.disabled = true;
    submit.textContent = "Saving event…";
    $("analysisResult").hidden = true;
    $("analysisWaiting").hidden = true;
    $("analysisLoading").hidden = false;
    $("studioStatus").textContent = "Saving your event to the workspace.";
    try {
      const receipt = await api("/api/v1/events/ingest", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ sourceId: $("studioSource").value, eventType: $("studioType").value, rawPayload: payload.value }),
        signal: AbortSignal.timeout(15000)
      });
      current = {id: receipt.eventId};
      try { sessionStorage.setItem("omnistream-latest-analysis", receipt.eventId); } catch {}
      paint({ id: receipt.eventId, status: "RECEIVED" });
      submitting = false;
      watch(receipt.eventId);
    } catch (failure) {
      submitting = false;
      $("analysisLoading").hidden = true;
      setBusy(false);
      error("Could not confirm acceptance. " + failure.message + " Check the event stream before resubmitting.");
    }
  });

  payload.addEventListener("input", () => $("studioCount").textContent = payload.value.length.toLocaleString() + " / 65,536");
  $("studioFile").addEventListener("change", async event => {
    const file = event.target.files[0];
    if (!file) return;
    try {
      if (file.size > 262144) throw Error("Choose a text file of at most 65,536 characters.");
      const text = await file.text();
      if (text.length > 65536) throw Error("This file exceeds the 65,536-character limit.");
      payload.value = text;
      payload.dispatchEvent(new Event("input"));
      error();
    } catch (failure) { error(failure.message); }
  });
  document.querySelectorAll("[data-studio-example]").forEach(button => button.onclick = () => loadExample(button.dataset.studioExample));
  $("studioInspect").onclick = () => current && inspect(current);
  $("studioCheck").onclick = () => current && watch(current.id);
  document.addEventListener("workspace:updated", provider);
  if (!previewOnly) {
    document.querySelectorAll(".ingest").forEach(button => button.onclick = () => location.hash = "#ai");
    $("tryInvoice").onclick = () => { location.hash = "#ai"; loadExample("invoice"); };
  }
  loadExample("invoice");
  initialConnection.then(async () => {
    provider();
    if (previewOnly) { submit.textContent = "Inspect sample analysis ↗"; return; }
    let id;
    try { id = sessionStorage.getItem("omnistream-latest-analysis"); } catch {}
    if (id && key && /^[0-9a-f-]{36}$/i.test(id)) {
      try {
        const row = await api("/api/v1/events/" + id);
        if (revision !== 0) return;
        paint(row);
        if (!["COMPLETED", "FAILED"].includes(row.status)) watch(id);
      } catch { /* The event may belong to a previous workspace. */ }
    }
  });
})();
