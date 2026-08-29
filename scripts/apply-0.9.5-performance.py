from pathlib import Path
import re

p = Path('src/main.ts')
s = p.read_text(encoding='utf-8')

s = s.replace("let games:Game[]=[];let selected:string|null=null;let details:Details|null=null;let view='library';let mode:'grid'|'list'='grid';let query='';let filter='all';let sort='title';let libraryLimit=240;",
              "let games:Game[]=[];let selected:string|null=null;let details:Details|null=null;let view='library';let mode:'grid'|'list'='grid';let query='';let filter='all';let sort='title';let libraryLimit=48;let gameFingerprint='';")

s = s.replace("libraryLimit=240", "libraryLimit=48")
s = s.replace("libraryLimit+=240", "libraryLimit+=48")

old_refresh = "async function refresh(keep=false){games=await invoke<Game[]>('list_games');if(!keep&&!selected)selected=games[0]?.id||null;await renderView()}"
new_refresh = "const fingerprint=(items:Game[])=>items.map(g=>[g.id,g.title,g.favorite,g.status,g.total_seconds,g.installed,g.active,g.last_played_at,g.session_count,g.providers.join(',')].join('~')).join('|');\nasync function refresh(keep=false,forceRender=true){const next=await invoke<Game[]>('list_games');const nextFingerprint=fingerprint(next);const changed=nextFingerprint!==gameFingerprint;games=next;gameFingerprint=nextFingerprint;if(!keep&&!selected)selected=games[0]?.id||null;if(forceRender||changed)await renderView()}"
if old_refresh not in s:
    raise SystemExit('refresh pattern not found')
s = s.replace(old_refresh, new_refresh)

old_boot = "async function bootstrap(){shell();await refresh();try{await invoke('sync_provider',{provider:'steam'});await refresh(true)}catch{}window.setTimeout(async()=>{try{const u=await invoke<UpdateInfo>('check_for_updates');if(u.available)toast(`Ludex ${u.latest_version} disponível em Configurações.`)}catch{}},1800)}\nvoid bootstrap();window.setInterval(()=>{if(['library','recent','favorites','installed'].includes(view))void refresh(true)},5000);"
new_boot = "async function bootstrap(){shell();await refresh();window.setTimeout(async()=>{try{const u=await invoke<UpdateInfo>('check_for_updates');if(u.available)toast(`Ludex ${u.latest_version} disponível em Configurações.`)}catch{}},1800)}\nvoid bootstrap();window.setInterval(()=>{if(['library','recent','favorites','installed'].includes(view))void refresh(true,false)},15000);"
if old_boot not in s:
    raise SystemExit('bootstrap pattern not found')
s = s.replace(old_boot, new_boot)

# Keep artwork lazy and reduce prefetch radius for large libraries.
s = s.replace("rootMargin:'300px'", "rootMargin:'120px'")

# UI version label.
s = re.sub(r"<span>0\.9\.\d+</span>", "<span>0.9.5</span>", s, count=1)
p.write_text(s, encoding='utf-8')

# Version bumps.
package = Path('package.json')
t = package.read_text(encoding='utf-8').replace('"version": "0.9.4"', '"version": "0.9.5"').replace('"version": "0.9.3"', '"version": "0.9.5"')
package.write_text(t, encoding='utf-8')

cargo = Path('src-tauri/Cargo.toml')
t = cargo.read_text(encoding='utf-8').replace('version = "0.9.4"', 'version = "0.9.5"').replace('version = "0.9.3"', 'version = "0.9.5"')
cargo.write_text(t, encoding='utf-8')

tauri = Path('src-tauri/tauri.conf.json')
t = tauri.read_text(encoding='utf-8').replace('"version": "0.9.4"', '"version": "0.9.5"').replace('"version": "0.9.3"', '"version": "0.9.5"')
tauri.write_text(t, encoding='utf-8')

print('0.9.5 large-library performance patch applied')
