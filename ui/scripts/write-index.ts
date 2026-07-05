import { createHash } from "node:crypto";
import {
  copyFile,
  readdir,
  readFile,
  rename,
  rm,
  writeFile,
} from "node:fs/promises";
import { join } from "node:path";

const distDir = join(import.meta.dir, "..", "dist");
const assetsDir = join(distDir, "assets");
const srcDir = join(import.meta.dir, "..", "src");
const templatePath = join(import.meta.dir, "index.template");
const assets = await readdir(assetsDir);
const script = assets.find((asset) => asset.endsWith(".js"));
const stylesheet = await fingerprintStylesheet(assets);

if (!script) {
  throw new Error("Bun build did not emit a JavaScript bundle");
}

const logoUrl = await fingerprintLogo();

const loaderStyle = `<style>
  #app-loader {
    position: fixed;
    inset: 0;
    z-index: 99999;
    display: flex;
    align-items: center;
    justify-content: center;
    background: #fff;
    opacity: 1;
    transition: opacity 0.4s ease;
    pointer-events: auto;
  }
  #app-loader.app-loader--fade-out {
    opacity: 0;
    pointer-events: none;
  }
  #app-loader img {
    width: 80px;
    height: 80px;
  }
</style>`;

function loaderHtml(logoUrl: string) {
  return `<div id="app-loader"><img src="${logoUrl}" alt="" /></div>`;
}

await rm(join(distDir, "index.html"), { force: true });

const template = await readFile(templatePath, "utf8");

await writeFile(
  join(distDir, "index.html"),
  template
    .replace(
      "{{favicon}}",
      `<link rel="icon" type="image/svg+xml" href="${logoUrl}" />`,
    )
    .replace("{{logoUrl}}", logoUrl)
    .replace("{{loaderStyle}}", loaderStyle)
    .replace("{{loaderHtml}}", loaderHtml(logoUrl))
    .replace(
      "{{styles}}",
      stylesheet
        ? `<link rel="stylesheet" href="/assets/${stylesheet}" />`
        : "",
    )
    .replace("{{script}}", `/assets/${script}`),
);

async function fingerprintStylesheet(assetList: string[]) {
  const stylesheet = assetList.find((asset) => asset.endsWith(".css"));
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

async function fingerprintLogo() {
  const svgPath = join(srcDir, "assets", "logo.svg");
  const contents = await readFile(svgPath);
  const hash = createHash("sha256").update(contents).digest("hex").slice(0, 8);
  const fingerprintedName = `logo-${hash}.svg`;
  await copyFile(svgPath, join(assetsDir, fingerprintedName));
  return `/assets/${fingerprintedName}`;
}
