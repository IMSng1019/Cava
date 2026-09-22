#!/usr/bin/env node
// Cava testbed helper: resolve Fabric loader/installer versions for a MC version,
// and print the exact server-launcher URL. Output is plain key=value lines (parsed by .ps1).
// usage: node tools/meta.cjs [mcVersion]
const mc = process.argv[2] || '1.20.4';

async function j(u) {
  const r = await fetch(u, { redirect: 'follow' });
  if (!r.ok) throw new Error('HTTP ' + r.status + ' ' + u);
  return r.json();
}

(async () => {
  const list = await j('https://meta.fabricmc.net/v2/versions/loader/' + mc);
  const stable = list.filter(e => e.loader && e.loader.stable);
  const pick = stable[0] || list[0];
  const loader = pick.loader.version;
  const inst = await j('https://meta.fabricmc.net/v2/versions/installer');
  const instStable = inst.filter(e => e.stable)[0] || inst[0];
  const installer = instStable.version;
  const url = 'https://meta.fabricmc.net/v2/versions/loader/' + mc + '/' + loader + '/' + installer + '/server/jar';
  console.log('mc=' + mc);
  console.log('loader=' + loader);
  console.log('loader_stable=' + pick.loader.stable);
  console.log('loader_build=' + pick.loader.build);
  console.log('loader_entries_total=' + list.length);
  console.log('loader_stable_total=' + stable.length);
  console.log('installer=' + installer);
  console.log('installer_stable=' + instStable.stable);
  console.log('server_launcher_url=' + url);
  console.log('intermediary=' + mc);
  console.log('sponge_mixin=' + (pick.launcherMeta && pick.launcherMeta.libraries && pick.launcherMeta.libraries.common
    ? (pick.launcherMeta.libraries.common.find(l => /sponge-mixin/.test(l.name)) || {}).name : 'n/a'));
})().catch(e => { console.error('ERROR ' + e.message); process.exit(1); });
