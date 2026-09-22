
const fs = require('fs');
const path = require('path');
const out = 'J:/mc/Cava/tools/decompiler';
fs.mkdirSync(out, { recursive: true });
const urls = [
  'https://repo1.maven.org/maven2/org/vineflower/vineflower/1.11.1/vineflower-1.11.1.jar',
  'https://repo1.maven.org/maven2/org/vineflower/vineflower/1.10.1/vineflower-1.10.1.jar',
];
(async () => {
  for (const u of urls) {
    try {
      const r = await fetch(u);
      console.log(u, r.status);
      if (!r.ok) continue;
      const buf = Buffer.from(await r.arrayBuffer());
      const f = path.join(out, path.basename(u));
      fs.writeFileSync(f, buf);
      console.log('saved', f, buf.length);
      return;
    } catch (e) { console.log(u, 'FAIL', e.message); }
  }
})();
