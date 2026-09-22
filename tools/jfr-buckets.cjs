#!/usr/bin/env node
/*
Cava P0-E: turn a JFR ExecutionSample dump into tick-bucket shares.

Why JFR: on this host spark can only write a protobuf .sparkprofile (no text call tree) and
jcmd cannot attach to the server (the file sandbox denies the attach pipe). A JFR recording
armed at JVM start with -XX:StartFlightRecording works, and "jfr print" produces TEXT.

usage:
  jfr print --events jdk.ExecutionSample --stack-depth 40 <file> > samples.txt
  node tools/jfr-buckets.cjs <samples.txt> [--thread "Server thread"] [--report]

Buckets are attributed to the INNERMOST (closest to the top of the stack) matching frame,
in the order the rules are listed. Run with --report first to see the hot frames and fix
the patterns to what the data actually contains.
*/
const fs = require('fs');
const path = require('path');
const { loadMappings } = require('./yarnmap.cjs');

const args = process.argv.slice(2);
const file = args[0];
if (!file) { console.error('usage: node jfr-buckets.cjs <samples.txt> [--thread X] [--report]'); process.exit(2); }
const threadArgIdx = args.indexOf('--thread');
const threadName = threadArgIdx >= 0 ? args[threadArgIdx + 1] : 'Server thread';
const report = args.includes('--report');

// ---- bucket rules (intermediary names as they appear in a production stack trace) ----
const BUCKETS = [
  { name: '寻路 pathfinding',   patterns: [/class_13\.method_(52|54)\b/, /class_13\b/] },
  { name: '方块tick 含红石',     patterns: [/class_1937\.method_8\d\d\d/, /TickScheduler/, /method_8675/, /method_20890/, /class_2789\b/, /class_2790\b/, /class_2422\b/, /class_2442\b/] },
  { name: '实体tick',           patterns: [/method_18456/, /method_18713/, /method_5773\b/, /method_6091\b/, /method_5784\b/, /method_6070\b/] },
  { name: '区块生成',           patterns: [/class_2794\b/, /ChunkGenerator/, /class_2851\b/, /ChunkStatus/, /method_17207/, /class_3898\b/] },
];

const m = (() => { try { return loadMappings(); } catch (e) { return null; } })();
function resolve(frame) {
  if (!m) return '';
  const mm = /^net\.minecraft\.(class_\d+)\.(method_\d+)/.exec(frame);
  if (!mm) return '';
  const hit = m.methodToClass.get(mm[1] + '.' + mm[2]);
  return hit ? hit.cls + '.' + hit.named : '';
}

const text = fs.readFileSync(file, 'utf8');
const blocks = text.split(/^(?=jdk\.ExecutionSample \{)/m);
let samples = 0, kept = 0;
const selfCount = new Map();
const bucketCount = new Map(BUCKETS.map(b => [b.name, 0]));
let unmatched = 0;
const unmatchedTop = new Map();

for (const b of blocks) {
  if (!b.startsWith('jdk.ExecutionSample {')) continue;
  samples++;
  const tm = /sampledThread = "([^"]+)"/.exec(b);
  if (!tm || tm[1] !== threadName) continue;
  kept++;
  const st = /stackTrace = \[([\s\S]*?)\n\s*\]/.exec(b);
  if (!st) continue;
  const frames = st[1].split(/\r?\n/).map(l => l.trim()).filter(Boolean)
    .map(l => l.replace(/\(.*$/, '').trim()).filter(Boolean);
  if (!frames.length) continue;
  const top = frames[0];
  selfCount.set(top, (selfCount.get(top) || 0) + 1);
  let hit = null;
  for (const f of frames) {
    for (const rule of BUCKETS) { if (rule.patterns.some(p => p.test(f))) { hit = rule.name; break; } }
    if (hit) break;
  }
  if (hit) bucketCount.set(hit, bucketCount.get(hit) + 1);
  else { unmatched++; unmatchedTop.set(top, (unmatchedTop.get(top) || 0) + 1); }
}

console.log('samples total            = ' + samples);
console.log('samples on "' + threadName + '" = ' + kept);
console.log('');
console.log('bucket shares (innermost matching frame):');
for (const [n, c] of [...bucketCount.entries()].sort((a, b) => b[1] - a[1])) {
  console.log('  ' + n.padEnd(18) + String(c).padStart(6) + '  ' + (kept ? (100 * c / kept).toFixed(1) : '0') + '%');
}
console.log('  ' + 'other'.padEnd(18) + String(unmatched).padStart(6) + '  ' + (kept ? (100 * unmatched / kept).toFixed(1) : '0') + '%');
console.log('');
if (report) {
  const top = (map, n, title) => {
    console.log(title);
    for (const [f, c] of [...map.entries()].sort((a, b) => b[1] - a[1]).slice(0, n)) {
      const y = resolve(f);
      console.log('  ' + String(c).padStart(6) + '  ' + f + (y ? '   <= ' + y : ''));
    }
    console.log('');
  };
  top(selfCount, 30, 'top 30 self frames (Server thread):');
  top(unmatchedTop, 20, 'top 20 self frames NOT matched by any bucket:');
}
