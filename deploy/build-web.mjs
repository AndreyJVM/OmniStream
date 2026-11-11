import { copyFile, mkdir, readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";

const root = fileURLToPath(new URL("../", import.meta.url));
const assets = path.join(root, "src/main/resources/static");
const output = path.join(root, "dist");
const configuredOrigin = process.env.WORKSPACE_ORIGIN?.trim();
let origin = "";
if (configuredOrigin) {
  const url = new URL(configuredOrigin);
  if (url.protocol !== "https:" || url.username || url.password || url.pathname !== "/" || url.search || url.hash) {
    throw new Error("WORKSPACE_ORIGIN must be the HTTPS origin of your running Omnistream server.");
  }
  origin = url.origin;
}
await mkdir(output, { recursive: true });
for (const name of ["mark.svg", "landing.css", "landing.js", "console.css", "console.js", "ai-workspace.css", "ai-workspace.js"]) {
  await copyFile(path.join(assets, name), path.join(output, name));
}
let landing = await readFile(path.join(assets, "index.html"), "utf8");
let workspace;
if (origin) {
  landing = landing.replaceAll('href="/workspace', 'href="' + origin + '/workspace')
    .replaceAll('href="/swagger-ui.html"', 'href="' + origin + '/swagger-ui.html"');
  // Direct workspace links and legacy fragment bookmarks follow the same handoff.
  workspace = `<!doctype html><html lang="en"><meta charset="utf-8"><title>Open workspace</title>
    <a id="workspace" href="${origin}/workspace">Continue to the live workspace</a>
    <script>location.replace(document.getElementById("workspace").href + location.hash);</script></html>`;
} else {
  workspace = (await readFile(path.join(assets, "workspace.html"), "utf8"))
    .replace("<body>", "<body data-preview-only>");
  landing = landing.replace('href="/swagger-ui.html">API reference', 'href="/workspace#help">Workspace guide');
}
await writeFile(path.join(output, "index.html"), landing);
await writeFile(path.join(output, "workspace.html"), workspace);
console.log(origin ? "Built landing page linked to the live workspace." : "Built landing page and read-only sample workspace.");
