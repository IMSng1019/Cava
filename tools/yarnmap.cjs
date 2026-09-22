#!/usr/bin/env node
/*
Cava P0-E: minimal Yarn <-> intermediary lookup built directly from the local mappings.tiny.
Exists because profiler output from a PRODUCTION server contains intermediary names
(net.minecraft.class_3218.method_18456), which have to be resolved back to Yarn names.

usage:
  node tools/yarnmap.cjs build                       -> print stats
  node tools/yarnmap.cjs class ServerWorld
  node tools/yarnmap.cjs member ServerWorld tickNonPassenger
  node tools/yarnmap.cjs reverse class_3218 method_18456

mappings path: $env:CAVA_MAPPINGS or the known Loom cache path.
*/
const fs = require('fs');
const path = require('path');

const DEFAULT_MAPPINGS = 'C:\\Users\\郁小悟520\\.gradle\\caches\\fabric-loom\\1.20.4\\net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2\\mappings.tiny';

function loadMappings() {
  const file = process.env.CAVA_MAPPINGS || DEFAULT_MAPPINGS;
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
  const byNamed = new Map();     // 'net/minecraft/server/world/ServerWorld' -> { inter, methods: Map(named -> [inter]) }
  const byInter = new Map();     // 'class_3218' -> named simple path
  const methodToClass = new Map(); // 'class_3218.method_18456' -> { cls, named }
  let cur = null;
  for (const line of lines) {
    if (!line) continue;
    const p = line.split('\t');
    if (p.length === 4 && p[0] === 'c' && p[2] && p[2].indexOf('class_') > 0) {
      cur = { official: p[1], inter: p[2], named: p[3], methods: new Map() };
      byNamed.set(p[3], cur);
      byInter.set(p[2].split('/').pop(), p[3]);
      continue;
    }
    if (cur && line.charCodeAt(0) === 9) {
      const m = line.split('\t');
      if (m[1] === 'm' || m[1] === 'f') {
        const interName = m[4], namedName = m[5];
        if (m[1] === 'm') {
          if (!cur.methods.has(namedName)) cur.methods.set(namedName, []);
          cur.methods.get(namedName).push(interName);
          methodToClass.set(cur.inter.split('/').pop() + '.' + interName, { cls: cur.named, named: namedName });
        }
      }
    }
  }
  return { byNamed, byInter, methodToClass, file };
}

function yarnClassToInter(m, clsName) {
  const simple = clsName.indexOf('.') >= 0 ? clsName.replace(/\./g, '/') : clsName;
  for (const [named, v] of m.byNamed) {
    if (named === simple || named.endsWith('/' + simple)) return v.inter;
  }
  return null;
}

if (require.main === module) {
  const m = loadMappings();
  const [, , cmd, a, b] = process.argv;
  if (cmd === 'build' || !cmd) {
    console.log('mappings  = ' + m.file);
    console.log('classes   = ' + m.byNamed.size);
    console.log('methods   = ' + m.methodToClass.size);
  } else if (cmd === 'class') {
    const hit = yarnClassToInter(m, a);
    console.log(a + ' -> ' + (hit || 'NOT FOUND'));
  } else if (cmd === 'member') {
    const c = yarnClassToInter(m, a);
    const entry = m.byNamed.get([...m.byNamed.keys()].find(k => k === a || k.endsWith('/' + a)));
    if (!entry) { console.log('class not found: ' + a); process.exit(1); }
    const ids = entry.methods.get(b);
    console.log(a + '.' + b + ' -> ' + (ids ? ids.map(x => c + '.' + x).join(', ') : 'NOT FOUND'));
  } else if (cmd === 'reverse') {
    const hit = m.methodToClass.get(a + '.' + b);
    console.log(a + '.' + b + ' -> ' + (hit ? hit.cls + '.' + hit.named : 'NOT FOUND'));
  } else {
    console.error('unknown command');
    process.exit(2);
  }
}
module.exports = { loadMappings, yarnClassToInter };
