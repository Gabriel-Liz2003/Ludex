from pathlib import Path

p=Path('src/main.ts')
t=p.read_text(encoding='utf-8')
t=t.replace("import { invoke } from '@tauri-apps/api/core';","import { convertFileSrc, invoke } from '@tauri-apps/api/core';",1)
t=t.replace("let games:Game[]=[];let selected:string|null=null;let details:Details|null=null;let view='library';let mode:'grid'|'list'='grid';let query='';let filter='all';let sort='title';","let games:Game[]=[];let selected:string|null=null;let details:Details|null=null;let view='library';let mode:'grid'|'list'='grid';let query='';let filter='all';let sort='title';let libraryLimit=240;",1)
old="function cover(g:Game){return `<div class=\"cover-art ${g.active?'live':''}\"><span>${esc(initials(g.title))}</span>${g.active?'<b>EM JOGO</b>':''}</div>`}"
new="""function cover(g:Game){return `<div class=\"cover-art ${g.active?'live':''}\" data-cover-id=\"${g.id}\"><span>${esc(initials(g.title))}</span>${g.active?'<b>EM JOGO</b>':''}</div>`}
function artworkSrc(value:string){return /^(https?:|data:|blob:)/i.test(value)?value:convertFileSrc(value)}
function hydrateArtwork(){const observer=new IntersectionObserver(entries=>{for(const entry of entries){if(!entry.isIntersecting)continue;const node=entry.target as HTMLElement;observer.unobserve(node);const id=node.dataset.coverId;if(!id)continue;void invoke<Metadata|null>('get_metadata',{gameId:id}).then(meta=>{if(!meta?.cover)return;const img=document.createElement('img');img.loading='lazy';img.alt='';img.src=artworkSrc(meta.cover);img.onerror=()=>img.remove();node.prepend(img);const initialsNode=node.querySelector('span');if(initialsNode)(initialsNode as HTMLElement).style.display='none'}).catch(()=>{})}}},{rootMargin:'300px'});document.querySelectorAll<HTMLElement>('[data-cover-id]').forEach(node=>observer.observe(node))}"
"""
if old not in t: raise SystemExit('cover function not found')
t=t.replace(old,new,1)
t=t.replace("const content=document.querySelector('#content')!;const visible=filteredGames();","const content=document.querySelector('#content')!;const visible=filteredGames();const shown=visible.slice(0,libraryLimit);",1)
t=t.replace("${visible.map(g=>mode==='grid'?", "${shown.map(g=>mode==='grid'?",1)
needle=".join('')||'<p class=\"empty\">Nenhum jogo corresponde aos filtros.</p>'}</div><aside id=\"detail-pane\""
replacement=".join('')||'<p class=\"empty\">Nenhum jogo corresponde aos filtros.</p>'}${visible.length>shown.length?`<button id=\"load-more\" class=\"ghost load-more\">Carregar mais (${shown.length}/${visible.length})</button>`:''}</div><aside id=\"detail-pane\""
if needle not in t: raise SystemExit('catalog ending not found')
t=t.replace(needle,replacement,1)
needle="document.querySelectorAll<HTMLElement>('[data-game]').forEach(b=>b.onclick=()=>void selectGame(b.dataset.game!));if(selected)void selectGame(selected,false);"
replacement="document.querySelectorAll<HTMLElement>('[data-game]').forEach(b=>b.onclick=()=>void selectGame(b.dataset.game!));const more=document.querySelector<HTMLButtonElement>('#load-more');if(more)more.onclick=()=>{libraryLimit+=240;renderLibrary()};hydrateArtwork();if(selected)void selectGame(selected,false);"
if needle not in t: raise SystemExit('library event ending not found')
t=t.replace(needle,replacement,1)
# Remove the accidental duplicate adjacent Collections block.
block="""<div class=\"collections-inline\"><h3>Coleções</h3><div class=\"chips\">${memberships.map(c=>`<button data-membership=\"${c.id}\" class=\"${c.included?'active':''}\">${c.included?'✓ ':''}${esc(c.name)}</button>`).join('')||'<small>Nenhuma coleção criada.</small>'}</div></div>"""
double=block+block
if double in t:t=t.replace(double,block,1)
# Reset pagination whenever the user changes the effective dataset.
t=t.replace("query=(e.target as HTMLInputElement).value;if(['library'", "query=(e.target as HTMLInputElement).value;libraryLimit=240;if(['library'",1)
t=t.replace("b.onclick=()=>{filter=b.dataset.filter!;renderLibrary()}","b.onclick=()=>{filter=b.dataset.filter!;libraryLimit=240;renderLibrary()}",1)
t=t.replace("s.onchange=()=>{sort=s.value;renderLibrary()}","s.onchange=()=>{sort=s.value;libraryLimit=240;renderLibrary()}",1)
p.write_text(t,encoding='utf-8')
