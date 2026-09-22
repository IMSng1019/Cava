
const fs = require('fs');
const cp = require('child_process');
const JAVA = 'C:/Program Files/Java/jdk-21/bin/java.exe';
const VF = 'J:/mc/Cava/tools/decompiler/vineflower-1.11.1.jar';
const JAR = 'C:/Users/郁小悟520/.gradle/caches/fabric-loom/1.20.4/minecraft-extracted_server.jar';
const MAP = 'C:/Users/郁小悟520/.gradle/caches/fabric-loom/1.20.4/net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2/mappings.tiny';
const OUT = 'J:/mc/Cava/.decompiled/pf';
fs.rmSync(OUT, {recursive:true, force:true});
fs.mkdirSync(OUT, { recursive: true });
const args = ['-jar', VF, '--only=net/minecraft/class_13', '--log-level=warn', MAP, OUT];
const t0 = Date.now();
const r = cp.spawnSync(JAVA, args, { encoding: 'utf8', maxBuffer: 1 << 28, timeout: 240000 });
console.log('exit', r.status, 'signal', r.signal, 'ms', Date.now()-t0);
console.log('STDOUT:', (r.stdout||'').slice(0, 2000));
console.log('STDERR:', (r.stderr||'').slice(0, 2000));
const walk = (d) => fs.readdirSync(d, {withFileTypes:true}).flatMap(e => e.isDirectory() ? walk(d+'/'+e.name) : [d+'/'+e.name]);
try { console.log('files:\n' + walk(OUT).join('\n')); } catch(e) { console.log('no out dir'); }
