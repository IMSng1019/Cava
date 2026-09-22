#!/usr/bin/env node
// Cava P0-E: diff two worldhash --chunks TSV files (regionFile, x, z, sha256).
// usage: node tools/diff-chunks.cjs <a.tsv> <b.tsv> [maxPrint]
const fs = require('fs');
const [, , aPath, bPath, maxPrintArg] = process.argv;
if (!aPath || !bPath) { console.error('usage: node diff-chunks.cjs <a.tsv> <b.tsv> [maxPrint]'); process.exit(2); }
const maxPrint = maxPrintArg ? parseInt(maxPrintArg, 10) : 20;
const load = p => {
  const m = new Map();
  for (const line of fs.readFileSync(p, 'utf8').split(/\r?\n/)) {
    if (!line.trim()) continue;
    const [file, x, z, sha] = line.split('\t');
    m.set(file + ':' + x + ',' + z, sha);
  }
  return m;
};
const a = load(aPath), b = load(bPath);
const keys = new Set([...a.keys(), ...b.keys()]);
let same = 0, diff = 0;
const diffs = [], onlyA = [], onlyB = [];
for (const k of [...keys].sort()) {
  const va = a.get(k), vb = b.get(k);
  if (va && vb) { if (va === vb) same++; else { diff++; diffs.push(k); } }
  else if (va) onlyA.push(k);
  else onlyB.push(k);
}
if (process.argv.includes('--summary')) {
  const per = {};
  for (const k of [...keys].sort()) {
    const sub = k.split('/')[0];
    per[sub] = per[sub] || { same: 0, diff: 0, onlyA: 0, onlyB: 0 };
    const va = a.get(k), vb = b.get(k);
    if (va && vb) { if (va === vb) per[sub].same++; else per[sub].diff++; }
    else if (va) per[sub].onlyA++; else per[sub].onlyB++;
  }
  console.log('per-directory summary:');
  for (const sub of Object.keys(per).sort()) {
    const p = per[sub];
    console.log('  ' + sub.padEnd(10) + ' same=' + p.same + ' differ=' + p.diff + ' onlyA=' + p.onlyA + ' onlyB=' + p.onlyB);
  }
}
console.log('A = ' + aPath + '  (' + a.size + ' chunks)');
console.log('B = ' + bPath + '  (' + b.size + ' chunks)');
console.log('identical   = ' + same);
console.log('differing   = ' + diff);
console.log('only in A   = ' + onlyA.length + (onlyA.length ? '  e.g. ' + onlyA.slice(0, 5).join(' ') : ''));
console.log('only in B   = ' + onlyB.length + (onlyB.length ? '  e.g. ' + onlyB.slice(0, 5).join(' ') : ''));
if (diffs.length) console.log('first differing: ' + diffs.slice(0, maxPrint).join(' '));
process.exit(diff === 0 && onlyA.length === 0 && onlyB.length === 0 ? 0 : 1);
