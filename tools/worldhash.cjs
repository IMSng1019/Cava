#!/usr/bin/env node
/*
Cava P0-E: deterministic world digest.

Why this exists: a raw SHA-256 over .mca files is NOT comparable between two runs --
the 8 KiB region header stores a per-chunk "last modified" timestamp. This tool
hashes the *decompressed chunk payloads* (sorted by file + chunk index) instead, so
two runs that produced the same world produce the same digest.

usage:
  node tools/worldhash.cjs <worldDir> [--json <out.json>] [--chunks <out.tsv>] [--quiet]

Digest fields:
  digestPayload : sha256 over "<file>\t<index>\t<sha256(payload)>" lines, sorted -> COMPARABLE
  digestRaw     : sha256 over raw file bytes -> NOT comparable (timestamps inside)
  levelDat.gametime / dayTime : level tick counters (NBT "Time"/"DayTime")
*/
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const crypto = require('crypto');

const sha = b => crypto.createHash('sha256').update(b).digest('hex');

// ---------------- minimal NBT reader ----------------
function readNbt(buf) {
  let p = 0;
  const u1 = () => buf[p++];
  const i2 = () => { const v = buf.readInt16BE(p); p += 2; return v; };
  const i4 = () => { const v = buf.readInt32BE(p); p += 4; return v; };
  const i8 = () => { const v = buf.readBigInt64BE(p); p += 8; return v; };
  const f4 = () => { const v = buf.readFloatBE(p); p += 4; return v; };
  const f8 = () => { const v = buf.readDoubleBE(p); p += 8; return v; };
  const str = () => { const n = buf.readUInt16BE(p); p += 2; const s = buf.toString('utf8', p, p + n); p += n; return s; };
  function payload(type) {
    switch (type) {
      case 1: return u1() << 24 >> 24;
      case 2: return i2();
      case 3: return i4();
      case 4: return i8().toString();
      case 5: return f4();
      case 6: return f8();
      case 7: { const n = i4(); const b = buf.subarray(p, p + n); p += n; return '<byte[' + n + ']>'; }
      case 8: return str();
      case 9: {
        const et = u1(); const n = i4(); const out = [];
        for (let i = 0; i < n; i++) out.push(payload(et));
        return out;
      }
      case 10: {
        const out = {};
        for (;;) { const t = u1(); if (t === 0) break; const nm = str(); out[nm] = payload(t); }
        return out;
      }
      case 11: { const n = i4(); const out = []; for (let i = 0; i < n; i++) out.push(i4()); return out; }
      case 12: { const n = i4(); const out = []; for (let i = 0; i < n; i++) out.push(i8().toString()); return out; }
      default: throw new Error('nbt: bad tag type ' + type + ' at ' + p);
    }
  }
  const rootType = u1();
  if (rootType !== 10) throw new Error('nbt: root is not a compound');
  str(); // root name
  return payload(10);
}

// ---------------- region file ----------------
function readRegion(file) {
  const raw = fs.readFileSync(file);
  const chunks = [];
  if (raw.length < 8192) return { raw, chunks };
  for (let i = 0; i < 1024; i++) {
    const o = i * 4;
    const off = (raw[o] << 16) | (raw[o + 1] << 8) | raw[o + 2];
    const cnt = raw[o + 3];
    if (off === 0 || cnt === 0) continue;
    const start = off * 4096;
    if (start + 5 > raw.length) continue;
    const len = raw.readUInt32BE(start);
    const comp = raw[start + 4];
    const body = raw.subarray(start + 5, start + 4 + len);
    let data;
    try {
      if (comp === 1) data = zlib.gunzipSync(body);
      else if (comp === 2) data = zlib.inflateSync(body);
      else if (comp === 3) data = Buffer.from(body);
      else { chunks.push({ i, x: i % 32, z: Math.floor(i / 32), sha: 'unsupported-compression-' + comp, bytes: 0 }); continue; }
    } catch (e) {
      chunks.push({ i, x: i % 32, z: Math.floor(i / 32), sha: 'decompress-error:' + e.message, bytes: 0 });
      continue;
    }
    chunks.push({ i, x: i % 32, z: Math.floor(i / 32), sha: sha(data), bytes: data.length });
  }
  return { raw, chunks };
}

// ---------------- main ----------------
const worldDir = process.argv[2];
if (!worldDir || !fs.existsSync(worldDir)) { console.error('usage: node worldhash.cjs <worldDir> [--json out] [--chunks out] [--quiet]'); process.exit(2); }
const arg = name => { const i = process.argv.indexOf(name); return i >= 0 ? process.argv[i + 1] : null; };
const jsonOut = arg('--json');
const chunksOut = arg('--chunks');
const quiet = process.argv.includes('--quiet');

const regionFiles = [];
for (const sub of ['region', 'entities', 'poi']) {
  const d = path.join(worldDir, sub);
  if (!fs.existsSync(d)) continue;
  for (const f of fs.readdirSync(d).sort()) if (f.endsWith('.mca')) regionFiles.push({ sub, f, full: path.join(d, f) });
}

const rawLines = [];
const payLines = [];
const chunkLines = [];
const perFile = {};
let chunkCount = 0;
for (const rf of regionFiles) {
  const { raw, chunks } = readRegion(rf.full);
  const key = rf.sub + '/' + rf.f;
  rawLines.push(key + '\t' + sha(raw));
  const sorted = chunks.slice().sort((a, b) => a.i - b.i);
  for (const c of sorted) {
    payLines.push(key + '\t' + c.i + '\t' + c.sha);
    chunkLines.push([key, c.x, c.z, c.sha].join('\t'));
    chunkCount++;
  }
  perFile[key] = { chunks: chunks.length, rawSha256: sha(raw) };
}

// level.dat
const ldPath = path.join(worldDir, 'level.dat');
const levelDat = { present: fs.existsSync(ldPath) };
if (levelDat.present) {
  const gz = fs.readFileSync(ldPath);
  levelDat.rawSha256 = sha(gz);
  try {
    const nbt = readNbt(zlib.gunzipSync(gz));
    const d = nbt.Data || {};
    levelDat.scalars = {
      Time: d.Time, DayTime: d.DayTime, LastPlayed: d.LastPlayed,
      LevelName: d.LevelName, Version: d.Version && d.Version.Name,
      SpawnX: d.SpawnX, SpawnY: d.SpawnY, SpawnZ: d.SpawnZ,
      GameType: d.GameType, Difficulty: d.Difficulty,
      raining: d.raining, thundering: d.thundering, clearWeatherTime: d.clearWeatherTime,
      seed: d.WorldGenSettings && d.WorldGenSettings.seed,
      generateFeatures: d.WorldGenSettings && d.WorldGenSettings.generate_features,
    };
  } catch (e) { levelDat.error = e.message; }
}

const digestRaw = sha(rawLines.join('\n'));
const digestPayload = sha(payLines.join('\n'));

const result = {
  worldDir: path.resolve(worldDir),
  regionFiles: regionFiles.length,
  chunks: chunkCount,
  digestPayload,
  digestRaw,
  levelDat,
  perFile,
};

if (jsonOut) { fs.mkdirSync(path.dirname(path.resolve(jsonOut)), { recursive: true }); fs.writeFileSync(jsonOut, JSON.stringify(result, null, 2)); }
if (chunksOut) { fs.mkdirSync(path.dirname(path.resolve(chunksOut)), { recursive: true }); fs.writeFileSync(chunksOut, chunkLines.join('\n') + '\n'); }
if (!quiet) {
  console.log('worldDir      = ' + result.worldDir);
  console.log('regionFiles   = ' + result.regionFiles + '   chunks = ' + result.chunks);
  console.log('digestPayload = ' + digestPayload + '   <- comparable between runs');
  console.log('digestRaw     = ' + digestRaw + '   <- NOT comparable (region header timestamps)');
  if (levelDat.present) console.log('level.dat     = ' + JSON.stringify(levelDat.scalars || levelDat.error));
}
process.exit(0);
