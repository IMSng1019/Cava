#!/usr/bin/env node
/*
Cava P0-E: decode a spark .sparkprofile (raw protobuf) and report SELF-TIME shares.

Schema (read with javap from the server's own spark jar - SparkSamplerProtos):
  SamplerData      : 1=metadata 2=repeated ThreadNode 3=class_sources 5=method_sources ...
  ThreadNode       : 1=name 3=repeated StackTraceNode 4=repeated double times 5=repeated int32 children_refs
  StackTraceNode   : 3=class_name 4=method_name 6=line_number 7=method_desc
                     8=repeated double times 9=repeated int32 children_refs
"times" is a per-time-window array of sample counts (kept as double on the wire).
The thread node carries the FLAT list of all stack nodes; children_refs are indices into it.

What this gives and what it does NOT give:
  + real sampler counts attributed to the owning class/method (self time)
  + a class-family breakdown that maps onto the four Cava buckets
  - it is NOT the inclusive call-tree share; a method that only spends time inside
    callees shows ~0 self time here.

usage: node tools/sparkprofile-buckets.cjs <file.sparkprofile> [--top 25]
*/
const fs = require('fs');
const { loadMappings } = require('./yarnmap.cjs');

function readVarint(b, p) { let r = 0, s = 0, n = 0; for (;;) { const c = b[p++]; n++; r += (c & 0x7f) * Math.pow(2, s); s += 7; if (!(c & 0x80)) return [r, p]; if (n > 10) throw new Error('varint'); } }
function fields(b) {
  const out = [];
  let p = 0;
  while (p < b.length) {
    const [key, p2] = readVarint(b, p); p = p2;
    const field = key >> 3, wire = key & 7;
    if (wire === 0) { const [v, p3] = readVarint(b, p); p = p3; out.push({ field, wire, v }); }
    else if (wire === 2) { const [len, p3] = readVarint(b, p); p = p3; out.push({ field, wire, bytes: b.subarray(p, p + len) }); p += len; }
    else if (wire === 1) { out.push({ field, wire, d: b.readDoubleLE(p) }); p += 8; }
    else if (wire === 5) { out.push({ field, wire, f: b.readFloatLE(p) }); p += 4; }
    else throw new Error('wire ' + wire + ' at ' + p);
  }
  return out;
}
const first = (fs2, f) => fs2.find(x => x.field === f);
const str = (fs2, f) => { const x = first(fs2, f); return x && x.bytes ? x.bytes.toString('utf8') : ''; };
const int = (fs2, f) => { const x = first(fs2, f); return x && x.wire === 0 ? x.v : 0; };
const packedDoubles = (fs2, f) => {
  const out = [];
  for (const x of fs2.filter(y => y.field === f)) {
    if (x.wire === 2) { for (let p = 0; p + 8 <= x.bytes.length; p += 8) out.push(x.bytes.readDoubleLE(p)); }
    else if (x.wire === 1) out.push(x.d);
    else if (x.wire === 0) out.push(x.v);
  }
  return out;
};
const packedInts = (fs2, f) => {
  const out = [];
  for (const x of fs2.filter(y => y.field === f)) {
    if (x.wire === 2) { let p = 0; while (p < x.bytes.length) { const [v, p2] = readVarint(x.bytes, p); p = p2; out.push(v); } }
    else if (x.wire === 0) out.push(x.v);
  }
  return out;
};

const treeIdx = process.argv.indexOf('--tree');
const treeMinPct = treeIdx >= 0 ? parseFloat(process.argv[treeIdx + 1]) : 100;
const file = process.argv[2];
const topIdx = process.argv.indexOf('--top');
const topN = topIdx >= 0 ? parseInt(process.argv[topIdx + 1], 10) : 25;
const buf = fs.readFileSync(file);
const top = fields(buf);
const threads = top.filter(x => x.field === 2 && x.wire === 2).map(x => {
  const tf = fields(x.bytes);
  return {
    name: str(tf, 1),
    times: packedDoubles(tf, 4),
    nodes: tf.filter(y => y.field === 3).map(y => {
      const nf = fields(y.bytes);
      return { cls: str(nf, 3), mth: str(nf, 4), line: int(nf, 6), desc: str(nf, 7), times: packedDoubles(nf, 8), refs: packedInts(nf, 9) };
    }),
    refs: packedInts(tf, 5),
  };
});

let map = null;
try { map = loadMappings(); } catch (e) { /* optional */ }
const named = new Map();
function yarnOf(cls) {
  if (!map) return '';
  const simple = cls.split('.').pop();
  if (named.has(simple)) return named.get(simple);
  const hit = map.byInter.get(simple) || '';
  named.set(simple, hit);
  return hit;
}

// bucket by Yarn package/class family
function bucketOf(cls, mth) {
  const y = yarnOf(cls) || cls;
  if (y.includes('/entity/ai/pathing/')) return '寻路 pathfinding';
  if (y.includes('/entity/')) return '实体 entity (含 AI/移动)';
  if (y.includes('/block/') || y.includes('/world/tick/')) return '方块tick 含红石 block tick';
  if (y.includes('/world/gen/') || y.includes('/structure/') || y.includes('/world/chunk/') || y.includes('/world/biome/')) return '区块生成 worldgen/chunk';
  return '其他 other';
}

// ---- build the call tree from children_refs (indices into the flat node array) ----
function buildTree(root, nodes) {
  const seen = new Set();
  const visit = (i, depth) => {
    const n = nodes[i];
    if (!n || depth > 200) return { node: n, kids: [] };
    return { node: n, kids: (n.refs || []).map(j => visit(j, depth + 1)) };
  };
  return (root.refs || []).map(i => visit(i, 0));
}
// inclusive share, counting a subtree only once per bucket
function bucketShares(tree, total, bucketOf2) {
  const per = new Map();
  const walk = (t2, stop) => {
    const n = t2.node;
    if (!n) return;
    const b = bucketOf2(n.cls, n.mth);
    const inc = n.times.reduce((x, y) => x + y, 0);
    if (b !== null) { per.set(b, (per.get(b) || 0) + inc); return; }  // matched: do not descend
    for (const k of t2.kids) walk(k, stop);
  };
  for (const k of tree) walk(k, false);
  return per;
}
const BUCKET = (cls) => {
  const y = yarnOf(cls) || cls;
  if (y.includes('/entity/ai/pathing/')) return '寻路 pathfinding';
  if (y.includes('/entity/')) return '实体 entity (含 AI/移动)';
  if (y.includes('/block/') || y.includes('/world/tick/')) return '方块tick 含红石 block tick';
  if (y.includes('/world/gen/') || y.includes('/structure/') || y.includes('/world/chunk/') || y.includes('/world/biome/')) return '区块生成 worldgen/chunk';
  return null;
};

const rootIdx = process.argv.indexOf('--root');
const rootSub = rootIdx >= 0 ? process.argv[rootIdx + 1] : null;
function findNode(tree, sub) {
  for (const k of tree) {
    if (!k.node) continue;
    const label = k.node.cls + '.' + k.node.mth;
    if (label.includes(sub)) return k;
    const r = findNode(k.kids, sub);
    if (r) return r;
  }
  return null;
}
for (const t2 of threads) {
  let total = t2.times.reduce((a, b) => a + b, 0);
  const nodes = t2.nodes;
  const maxRef = Math.max(-1, ...nodes.map(n => Math.max(-1, ...(n.refs || []))));
  let tree = buildTree(t2, nodes);
  if (rootSub) {
    const hitNode = findNode(tree, rootSub);
    if (hitNode) { tree = [hitNode]; total = hitNode.node.times.reduce((a, b) => a + b, 0); console.log('--- rooted at ' + rootSub + ' ---'); }
    else console.log('!! root pattern not found: ' + rootSub);
  }
  console.log('');
  console.log('thread "' + t2.name + '"  total samples (root times) = ' + total.toFixed(0) + '   nodes=' + nodes.length + '   maxRef=' + maxRef);
  console.log('--- top-level call tree (depth<=2, inclusive samples) ---');
  const show = (t3, d) => {
    const n = t3.node; if (!n) return;
    const inc = n.times.reduce((x, y) => x + y, 0);
    const y = yarnOf(n.cls);
    console.log('  '.repeat(d + 1) + (100 * inc / total).toFixed(1).padStart(5) + '%  ' + String(inc.toFixed(0)).padStart(7) + '  ' + (y || n.cls + '.' + n.mth));
    const minPct = treeMinPct;
    if (d < 8 && (100 * inc / total) >= minPct) for (const k of t3.kids) show(k, d + 1);
  };
  for (const k of tree) show(k, 0);
  console.log('--- FOUR BUCKETS (inclusive, subtree counted once) ---');
  const per = bucketShares(tree, total, BUCKET);
  let sum = 0;
  for (const [k, v] of [...per.entries()].sort((a, b) => b[1] - a[1])) { sum += v; console.log('  ' + k.padEnd(28) + String(v.toFixed(0)).padStart(8) + '  ' + (100 * v / total).toFixed(1) + '%'); }
  console.log('  ' + '(未归入以上四块的其余部分)'.padEnd(24) + String((total - sum).toFixed(0)).padStart(8) + '  ' + (100 * (total - sum) / total).toFixed(1) + '%');
}