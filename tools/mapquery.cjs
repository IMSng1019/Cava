#!/usr/bin/env node
// tools/mapquery.cjs -- look up Yarn <-> intermediary <-> official names in the local tiny v2 mapping.
// Verified input: C:/Users/<user>/.gradle/caches/fabric-loom/1.20.4/net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2/mappings.tiny
// tiny v2 header: tiny	2	0	official	intermediary	named
// class line : c	<official>	<intermediary>	<named>
// member line: 	<o|m|f>	<officialDesc>	<intermediaryDesc>	<intermediaryName>	<namedName>
//
// usage:
//   node tools/mapquery.cjs class PathNodeNavigator
//   node tools/mapquery.cjs class net/minecraft/pathfinding/PathNodeNavigator
//   node tools/mapquery.cjs method PathNodeNavigator findPathToAny
//   node tools/mapquery.cjs intermediary class_13
'use strict';
const fs = require('fs');
const path = require('path');

const DEFAULT_MAP = process.env.CAVA_MAPPINGS ||
  'C:/Users/\u90c1\u5c0f\u609f520/.gradle/caches/fabric-loom/1.20.4/net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2/mappings.tiny';

function load(file) {
  const text = fs.readFileSync(file, 'utf8');
  const classes = [];
  let cur = null;
  for (const raw of text.split(/\r?\n/)) {
    if (!raw) continue;
    if (raw.charCodeAt(0) === 0x63 /* c */) {
      const p = raw.split('\t');
      // A class line is exactly: c, official, intermediary, named.
      // NOTE: tiny v2 javadoc lines also start with 'c' but have a different shape --
      // the discriminant below is the one verified in docs/CAVA-launch-notes.md section 5.
      if (p.length === 4 && p[2].indexOf('class_') > 0) {
        cur = { official: p[1], intermediary: p[2], named: p[3], members: [] };
        classes.push(cur);
      }
      continue;
    }
    if (raw.charCodeAt(0) === 0x09 /* tab */ && cur) {
      const p = raw.split('\t');
      // ['', kind, officialDesc, intermediaryDesc, intermediaryName, namedName]
      if (p.length === 6) {
        cur.members.push({ kind: p[1], officialDesc: p[2], intermediaryDesc: p[3], intermediary: p[4], named: p[5] });
      }
    }
  }
  return classes;
}

function main() {
  const [mode, a, b] = process.argv.slice(2);
  if (!mode) {
    console.log('usage: node tools/mapquery.cjs <class|method|intermediary> <name> [member]');
    process.exit(2);
  }
  const file = DEFAULT_MAP;
  if (!fs.existsSync(file)) { console.error('mapping file not found: ' + file); process.exit(2); }
  const classes = load(file);
  let hit = null;
  if (mode === 'class') {
    hit = classes.find(c => c.named === a || c.named === 'net/minecraft/' + a || c.named.endsWith('/' + a));
  } else if (mode === 'intermediary') {
    hit = classes.find(c => c.intermediary === 'net/minecraft/' + a);
  } else if (mode === 'method') {
    hit = classes.find(c => c.named === a || c.named.endsWith('/' + a));
    if (!hit) { console.error('class not found: ' + a); process.exit(1); }
    const ms = hit.members.filter(m => m.named === b);
    console.log('class ' + hit.named + '  intermediary=' + hit.intermediary + '  official=' + hit.official);
    for (const m of ms) {
      console.log('  ' + m.kind + ' ' + m.named + m.intermediaryDesc + '   intermediary=' + m.intermediary + '  official=' + m.officialDesc);
    }
    console.log('  (' + ms.length + ' match(es))');
    return;
  }
  if (!hit) { console.error('not found: ' + a); process.exit(1); }
  console.log(JSON.stringify({
    named: hit.named, intermediary: hit.intermediary, official: hit.official,
    memberCount: hit.members.length,
    officialClassFile: hit.official + '.class',
  }, null, 2));
  if (process.env.CAVA_DUMP_MEMBERS === '1') {
    for (const m of hit.members) console.log([m.kind, m.named, m.intermediaryDesc, m.intermediary, m.officialDesc].join('\t'));
  }
}
main();
