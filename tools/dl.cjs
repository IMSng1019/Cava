#!/usr/bin/env node
// Cava testbed helper: minimal network downloader (PowerShell Invoke-WebRequest fails TLS on this host).
// usage:
//   node tools/dl.cjs <url>                 -> print body to stdout
//   node tools/dl.cjs <url> <outPath>       -> save body to outPath (creates parent dirs)
// exit codes: 0 ok, 1 http error, 2 network error
const fs = require('fs');
const path = require('path');

async function main() {
  const [, , url, outPath] = process.argv;
  if (!url) { console.error('usage: node dl.cjs <url> [outPath]'); process.exit(2); }
  let res;
  try {
    res = await fetch(url, { redirect: 'follow', headers: { 'user-agent': 'cava-testbed/1.0' } });
  } catch (e) {
    console.error('NETWORK ERROR: ' + e.message);
    process.exit(2);
  }
  if (!res.ok) { console.error('HTTP ' + res.status + ' ' + res.statusText + ' for ' + url); process.exit(1); }
  const buf = Buffer.from(await res.arrayBuffer());
  if (!outPath) {
    process.stdout.write(buf.toString('utf8'));
    return;
  }
  fs.mkdirSync(path.dirname(path.resolve(outPath)), { recursive: true });
  fs.writeFileSync(outPath, buf);
  console.error('OK ' + url + ' -> ' + outPath + ' (' + buf.length + ' bytes)');
}
main();
