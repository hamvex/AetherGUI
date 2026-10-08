import { copyFileSync, existsSync, mkdirSync, readFileSync } from "node:fs";
import { createReadStream } from "node:fs";
import { createHash } from "node:crypto";
import { resolve } from "node:path";

const target = "x86_64-pc-windows-msvc";
const destination = resolve("src-tauri/binaries", `aether-${target}.exe`);
const pins = JSON.parse(readFileSync(resolve("scripts/aether-pins.json"), "utf8")).windows;
const expectedBinary = pins.binary["aether-windows-x86_64.zip"];
const ptHelpers = pins.pt ?? {};

/** SHA-256 of a file, hex lowercase. */
async function sha256(path) {
  const hash = createHash("sha256");
  await new Promise((done, fail) => {
    createReadStream(path)
      .on("data", (chunk) => hash.update(chunk))
      .on("error", fail)
      .on("end", done);
  });
  return hash.digest("hex");
}

async function verifyOrThrow(path, expected, label) {
  const actual = await sha256(path);
  if (actual !== expected) {
    throw new Error(
      `${label} digest mismatch: expected ${expected}, got ${actual}. Re-run \`npm run fetch:core\`.`,
    );
  }
}

const useBundled = existsSync(destination) && !process.env.AETHER_CORE_BINARY;
if (useBundled) {
  // A pre-existing sidecar is verified against the pin instead of blindly trusted: a
  // tampered or stale file must fail the build here (process.rs enforces the same digest
  // at runtime; this catches it at packaging).
  await verifyOrThrow(destination, expectedBinary, "Bundled Aether core");
  console.log(`Using bundled Aether core at ${destination} (SHA-256 verified)`);
  await stagePrivacyHelpers();
  process.exit(0);
}

const candidates = [process.env.AETHER_CORE_BINARY, resolve("vendor/aether.exe")].filter(Boolean);
const source = candidates.find(existsSync);
if (!source) {
  throw new Error("Aether core is missing. Run `npm run fetch:core` or set AETHER_CORE_BINARY.");
}

mkdirSync(resolve("src-tauri/binaries"), { recursive: true });
copyFileSync(source, destination);
await verifyOrThrow(destination, expectedBinary, "Aether core");
console.log(`Bundling Aether core from ${source} (SHA-256 verified)`);
await stagePrivacyHelpers();

/** Privacy helpers (pt/): install beside the sidecar in the layout core_pt_dir() expects. */
async function stagePrivacyHelpers() {
  const ptDir = resolve("src-tauri/binaries/pt");
  mkdirSync(ptDir, { recursive: true });
  const vendorPt = process.env.AETHER_PT_DIR
    ? resolve(process.env.AETHER_PT_DIR)
    : resolve("vendor/pt");
  for (const [name, expected] of Object.entries(ptHelpers)) {
    const helperSource = resolve(vendorPt, name);
    if (!existsSync(helperSource)) {
      // The helper may already be staged under src-tauri/binaries/pt (fetch-aether.ps1 path);
      // verify whatever is present, but never ship an unverified file.
      const staged = resolve(ptDir, name);
      if (existsSync(staged)) {
        await verifyOrThrow(staged, expected, `pt/${name}`);
        console.log(`Using staged pt/${name} (SHA-256 verified)`);
        continue;
      }
      throw new Error(
        `Privacy helper pt/${name} is missing. Run \`npm run fetch:core\` (installs pt/ helpers) or set AETHER_PT_DIR.`,
      );
    }
    copyFileSync(helperSource, resolve(ptDir, name));
    await verifyOrThrow(resolve(ptDir, name), expected, `pt/${name}`);
    console.log(`Bundling pt/${name} (SHA-256 verified)`);
  }
}

// Wintun + xray are managed by fetch-xray.ps1 with their own pins; their digests are
// enforced at runtime (routing.rs XRAY_SHA256 / WINTUN_SHA256) and by fetch-time checks.
if (!existsSync(resolve("src-tauri/binaries/wintun.dll"))) {
  console.warn("Note: wintun.dll is not present; run `npm run fetch:routing` before packaging.");
}
