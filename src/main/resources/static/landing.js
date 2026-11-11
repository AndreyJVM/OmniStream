"use strict";

// Preserve workspace bookmarks created before the landing page existed.
const oldTabs = ["#overview", "#stream", "#help", "#ai"];
if (oldTabs.includes(location.hash))
  location.replace("/workspace" + location.hash);

const examples = {
  invoice: [
    "Invoice INV-42 for USD 85.00 is overdue.",
    "Financial",
    "Invoice ID",
    "INV-42",
    "Amount",
    "USD 85.00",
  ],
  system: [
    "Service orders-api timed out on host web-03.",
    "System log",
    "Service",
    "orders-api",
    "Host",
    "web-03",
  ],
  sensor: [
    "Sensor T-19 reports a temperature of 23.4°C.",
    "IoT telemetry",
    "Sensor",
    "T-19",
    "Temperature",
    "23.4°C",
  ],
};
for (const button of document.querySelectorAll("[data-example]")) {
  button.addEventListener("click", () => {
    const [text, category, field1, value1, field2, value2] =
      examples[button.dataset.example];
    for (const [id, value] of Object.entries({
      payload: "“" + text + "”",
      category,
      field1,
      value1,
      field2,
      value2,
    })) {
      document.getElementById(id).textContent = value;
    }
    for (const option of document.querySelectorAll("[data-example]")) {
      option.setAttribute("aria-pressed", String(option === button));
    }
  });
}

function paintSignal() {
  const canvas = document.getElementById("signal-art");
  const width = canvas.clientWidth,
    height = canvas.clientHeight;
  const scale = Math.min(window.devicePixelRatio || 1, 2);
  canvas.width = width * scale;
  canvas.height = height * scale;
  const ctx = canvas.getContext("2d");
  if (!ctx) return;
  ctx.scale(scale, scale);
  const gradient = ctx.createLinearGradient(0, 0, width, 0);
  gradient.addColorStop(0, "rgba(169,219,98,0.02)");
  gradient.addColorStop(0.56, "rgba(169,219,98,0.24)");
  gradient.addColorStop(1, "rgba(186,244,120,0.72)");
  ctx.strokeStyle = gradient;
  ctx.lineWidth = 0.85;
  for (let line = 0; line < 65; line++) {
    const t = line / 64;
    ctx.beginPath();
    for (let x = 0; x <= width + 3; x += 3) {
      const u = x / width;
      const y =
        height *
        (0.84 +
          0.13 * Math.sin(6.2 * u + 2.4 * t) +
          0.11 * Math.sin(10 * u - 3.6 * t) +
          t * 0.13 -
          u * u * 0.6);
      if (x === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    }
    ctx.stroke();
  }
}
paintSignal();
window.addEventListener("resize", paintSignal);
