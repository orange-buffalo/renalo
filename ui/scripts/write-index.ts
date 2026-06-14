import { readdir, readFile, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";

const distDir = join(import.meta.dir, "..", "dist");
const assetsDir = join(distDir, "assets");
const templatePath = join(import.meta.dir, "index.template");
const assets = await readdir(assetsDir);
const script = assets.find((asset) => asset.endsWith(".js"));
const stylesheet = assets.find((asset) => asset.endsWith(".css"));

if (!script) {
  throw new Error("Bun build did not emit a JavaScript bundle");
}

await rm(join(distDir, "index.html"), { force: true });

const template = await readFile(templatePath, "utf8");

await writeFile(
  join(distDir, "index.html"),
  template
    .replace(
      "{{styles}}",
      stylesheet
        ? `<link rel="stylesheet" href="/assets/${stylesheet}" />`
        : "",
    )
    .replace("{{script}}", `/assets/${script}`),
);
