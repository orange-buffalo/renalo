import { readdir, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";

const distDir = join(import.meta.dir, "..", "dist");
const assetsDir = join(distDir, "assets");
const assets = await readdir(assetsDir);
const script = assets.find((asset) => asset.endsWith(".js"));
const stylesheet = assets.find((asset) => asset.endsWith(".css"));

if (!script) {
  throw new Error("Bun build did not emit a JavaScript bundle");
}

await rm(join(distDir, "index.html"), { force: true });

await writeFile(
  join(distDir, "index.html"),
  `<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Renalo</title>
    ${stylesheet ? `<link rel="stylesheet" href="/assets/${stylesheet}" />` : ""}
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/assets/${script}"></script>
  </body>
</html>
`,
);
