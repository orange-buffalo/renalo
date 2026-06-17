import { createHash } from "node:crypto";
import { readdir, readFile, rename, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";

const distDir = join(import.meta.dir, "..", "dist");
const assetsDir = join(distDir, "assets");
const templatePath = join(import.meta.dir, "index.template");
const assets = await readdir(assetsDir);
const script = assets.find((asset) => asset.endsWith(".js"));
const stylesheet = await fingerprintStylesheet(assets);

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

async function fingerprintStylesheet(assets: string[]) {
  const stylesheet = assets.find((asset) => asset.endsWith(".css"));
  if (!stylesheet || /-[a-f0-9]{8}\.css$/.test(stylesheet)) {
    return stylesheet;
  }

  const stylesheetPath = join(assetsDir, stylesheet);
  const contents = await readFile(stylesheetPath);
  const hash = createHash("sha256").update(contents).digest("hex").slice(0, 8);
  const fingerprintedStylesheet = stylesheet.replace(/\.css$/, `-${hash}.css`);

  await rename(stylesheetPath, join(assetsDir, fingerprintedStylesheet));
  return fingerprintedStylesheet;
}
