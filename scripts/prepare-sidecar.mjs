import { copyFileSync, existsSync, mkdirSync } from "node:fs";
import { resolve } from "node:path";

const target = "x86_64-pc-windows-msvc";
const destination = resolve("src-tauri/binaries", `aether-${target}.exe`);

if (existsSync(destination) && !process.env.AETHER_CORE_BINARY) {
  console.log(`Using bundled Aether core at ${destination}`);
  process.exit(0);
}

const candidates = [process.env.AETHER_CORE_BINARY, resolve("vendor/aether.exe")].filter(Boolean);
const source = candidates.find(existsSync);
if (!source) {
  throw new Error("Aether core is missing. Run `npm run fetch:core` or set AETHER_CORE_BINARY.");
}

mkdirSync(resolve("src-tauri/binaries"), { recursive: true });
copyFileSync(source, destination);
console.log(`Bundling Aether core from ${source}`);
