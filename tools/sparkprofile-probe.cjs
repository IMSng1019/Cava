#!/usr/bin/env node
/*
Cava P0-E: schema probe for spark .sparkprofile files.

spark writes the profiler result as RAW protobuf (not gzip - measured: first bytes 0a d5 80 01).
There is no schema on this machine, so this walks the wire format generically and prints a
compact tree: field numbers, wire types, printable strings, and packed-varint summaries.
Usage: node tools/sparkprofile-probe.cjs <file> [maxDepth] [maxNodes]
*/
const fs = require('fs');
const buf = fs.readFileSync(process.argv[2]);
const maxDepth = parseInt(process.argv[3] || '6', 10);
const maxNodes = parseInt(process.argv[4] || '120', 10);
let nodes = 0;

function readVarint(b, p) {
  let r = 0, s = 0, n = 0;
  for (;;) {
    const byte = b[p++]; n++;
    r += (byte & 0x7f) * Math.pow(2, s);
    s += 7;
    if (!(byte & 0x80)) return [r, p];
    if (n > 10) throw new Error('varint too long');
  }
}
const printable = b => {
  let s = '';
  for (const c of b) { if (c >= 32 && c < 127) s += String.fromCharCode(c); else return null; }
  return s;
};
// can this buffer be parsed as a sequence of well formed fields?
function looksLikeMessage(b) {
  let p = 0, fields = 0;
  try {
    while (p < b.length) {
      const [key, p2] = readVarint(b, p); p = p2;
      const wire = key & 7;
      if (wire === 0) { const [, p3] = readVarint(b, p); p = p3; }
      else if (wire === 1) p += 8;
      else if (wire === 2) { const [len, p3] = readVarint(b, p); p = p3 + len; }
      else if (wire === 5) p += 4;
      else return false;
      fields++;
      if (p > b.length) return false;
    }
    return fields > 0 && p === b.length;
  } catch { return false; }
}
function dump(b, depth, indent) {
  let p = 0;
  const groups = new Map();
  while (p < b.length && nodes < maxNodes) {
    const [key, p2] = readVarint(b, p); p = p2;
    const field = key >> 3, wire = key & 7;
    if (wire === 0) {
      const [v, p3] = readVarint(b, p); p = p3;
      const g = groups.get(field) || { wire, values: [] };
      g.values.push(v); groups.set(field, g);
    } else if (wire === 2) {
      const [len, p3] = readVarint(b, p); p = p3;
      const sub = b.subarray(p, p + len); p += len;
      const g = groups.get(field) || { wire, subs: [] };
      g.subs.push(sub); groups.set(field, g);
    } else if (wire === 5) { p += 4; } else if (wire === 1) { p += 8; } else return;
  }
  for (const [field, g] of [...groups.entries()].sort((a, b2) => a[0] - b2[0])) {
    if (g.wire === 0) {
      const v = g.values;
      const hex = v.map(x => x.toString(16)).join(',');
      console.log(indent + 'f' + field + ' varint x' + v.length + ' [' + (v.length > 300 ? hex.slice(0, 60) + '...' : hex) + ']');
      continue;
    }
    const subs = g.subs;
    // a repeated printable string?
    const strs = subs.map(printable);
    if (strs.every(s => s !== null && s.length > 0) && subs.length <= 40) {
      console.log(indent + 'f' + field + ' string x' + subs.length + ' ' + JSON.stringify(strs.slice(0, 12)));
      continue;
    }
    if (subs.length > 1 && subs.every(s => looksLikeMessage(s))) {
      console.log(indent + 'f' + field + ' message x' + subs.length + (depth < maxDepth ? '' : ' (depth limit)'));
      if (depth < maxDepth) for (const s of subs.slice(0, 6)) { if (nodes++ > maxNodes) break; console.log(indent + '  --'); dump(s, depth + 1, indent + '    '); }
      continue;
    }
    const one = subs[0];
    const s = printable(one);
    if (s !== null) { console.log(indent + 'f' + field + ' str(' + one.length + ') ' + JSON.stringify(s.slice(0, 80))); continue; }
    if (looksLikeMessage(one)) {
      console.log(indent + 'f' + field + ' msg(' + one.length + ')');
      nodes++;
      if (depth < maxDepth) dump(one, depth + 1, indent + '  ');
      continue;
    }
    console.log(indent + 'f' + field + ' bytes(' + one.length + ') ' + one.subarray(0, 24).toString('hex'));
  }
}
// optional: --field=N  dump only that top-level field (repeated -> all occurrences)
const fieldArg = process.argv.find(a => a.startsWith('--field='));
console.log('file=' + process.argv[2] + ' bytes=' + buf.length);
if (fieldArg) {
  const want = parseInt(fieldArg.split('=')[1], 10);
  let p = 0, hits = 0;
  while (p < buf.length) {
    const [key, p2] = readVarint(buf, p); p = p2;
    const field = key >> 3, wire = key & 7;
    if (wire === 2) {
      const [len, p3] = readVarint(buf, p); p = p3;
      const sub = buf.subarray(p, p + len); p += len;
      if (field === want) { hits++; console.log('--- occurrence ' + hits + ' (' + len + ' bytes)'); dump(sub, 0, ''); }
    } else if (wire === 0) { const [, p3] = readVarint(buf, p); p = p3; }
    else if (wire === 5) p += 4; else if (wire === 1) p += 8; else break;
  }
} else {
  dump(buf, 0, '');
}
