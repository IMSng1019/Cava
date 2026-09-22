#!/usr/bin/env node
/*
 * Cava P1 寻路测试向量 —— 一条命令复现（javac + java，不依赖 Gradle）。
 *
 *   node tools/gen-pathfind-vectors.cjs [outDir] [cases]
 *
 * 默认 outDir = src/test/resources/cava/oracle，cases = 10000。
 * 语义规格 + 二进制格式见 docs/CAVA-pathfind-oracle-spec.md 第 10 节。
 */
'use strict';

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const JDK = process.env.CAVA_JDK || 'C:\\Program Files\\Java\\jdk-21';
const JAVAC = path.join(JDK, 'bin', 'javac.exe');
const JAVA = path.join(JDK, 'bin', 'java.exe');

const repoRoot = path.resolve(__dirname, '..');
const outDir = process.argv[2] || path.join('src', 'test', 'resources', 'cava', 'oracle');
const cases = process.argv[3] || '10000';
const classesDir = path.join(repoRoot, 'build', 'oracle-classes');

function walk(dir, acc) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(p, acc);
    else if (entry.name.endsWith('.java')) acc.push(p);
  }
  return acc;
}

const sources = walk(path.join(repoRoot, 'src', 'test', 'java', 'cava', 'oracle'), []);
if (sources.length === 0) {
  console.error('找不到 src/test/java/cava/oracle/*.java');
  process.exit(1);
}
fs.mkdirSync(classesDir, { recursive: true });

console.log('[1/2] javac --release 21 ->', classesDir);
const jc = spawnSync(JAVAC, ['--release', '21', '-encoding', 'UTF-8', '-d', classesDir, ...sources], {
  stdio: 'inherit',
  cwd: repoRoot
});
if (jc.status !== 0) {
  console.error('javac 失败，退出码', jc.status);
  process.exit(jc.status || 1);
}

console.log('[2/2] java cava.oracle.OracleSelfTest', outDir, cases);
const jv = spawnSync(JAVA, ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
  '-cp', classesDir, 'cava.oracle.OracleSelfTest', outDir, cases], {
  stdio: 'inherit',
  cwd: repoRoot
});
process.exit(jv.status === null ? 1 : jv.status);
