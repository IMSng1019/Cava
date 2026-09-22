#!/usr/bin/env node
// Cava testbed helper: resolve (and optionally download) the vanilla Minecraft server jar
// straight from Mojang's piston meta, so the Fabric launcher never has to download it.
// usage: node tools/get-vanilla.cjs <mcVersion> [outPath]
const fs = require('fs');
const path = require('path');
const mc = process.argv[2] || '1.20.4';
const out = process.argv[3];

(async () => {
  const m = await (await fetch('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json')).json();
  const v = m.versions.find(x => x.id === mc);
  if (!v) throw new Error('version ' + mc + ' not in manifest');
  const meta = await (await fetch(v.url)).json();
  const url = meta.downloads.server.url;
  const sha1 = meta.downloads.server.sha1;
  const size = meta.downloads.server.size;
  console.log('mc=' + mc);
  console.log('vanilla_server_url=' + url);
  console.log('vanilla_server_sha1=' + sha1);
  console.log('vanilla_server_size=' + size);
  console.log('vanilla_server_jar=' + meta.downloads.server.url.split('/').pop());
  if (out) {
    const buf = Buffer.from(await (await fetch(url)).arrayBuffer());
    fs.mkdirSync(path.dirname(path.resolve(out)), { recursive: true });
    fs.writeFileSync(out, buf);
    const crypto = require('crypto');
    const got = crypto.createHash('sha1').update(buf).digest('hex');
    console.log('downloaded_to=' + out);
    console.log('downloaded_bytes=' + buf.length);
    console.log('sha1_match=' + (got === sha1));
  }
})().catch(e => { console.error('ERROR ' + e.message); process.exit(1); });
