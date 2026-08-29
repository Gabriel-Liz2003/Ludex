import { invoke } from '@tauri-apps/api/core';
import './styles.css';

type Game={id:string;title:string;platform:string;source:string;executable:string|null;favorite:boolean;status:string;total_seconds:number;installed:boolean;providers:string[];active:boolean;last_played_at:string|null;session_count:number};
type Installation={id:string;provider:string;external_id:string|null;executable:string|null;install_dir:string|null;installed:boolean};
type Details={game:Game;stats:{total_seconds:number;last_14_seconds:number;last_30_seconds:number;session_count:number;average_session_seconds:number;last_played_at:string|null};installations:Installation[];recent_sessions:{id:string;started_at:string;ended_at:string|null;duration_seconds:number;provider:string|null;recovered:boolean}[]};
type Provider={id:string;name:string;detected:boolean;root_path:string|null;games_found:number;last_sync:string|null;message:string;can_launch:boolean};
type Collection={id:string;name:string;kind:string;filter_json:string|null;game_count:number};
type Emulator={id:string;name:string;platform:string;executable:string;arguments_template:string;rom_directory:string|null;bios_directory:string|null;saves_directory:string|null;extensions:string;core:string|null;enabled:boolean};
type Rom={id:string;game_id:string;title:string;platform:string;path:string;emulator_id:string|null;hash_sha256:string|null;size_bytes:number|null;launch_args:string|null;core:string|null};
type Metadata={game_id:string;description:string|null;developer:string|null;publisher:string|null;release_date:string|null;genres:string|null;cover:string|null;hero:string|null;source:string;manual:boolean;updated_at:string};
type Stats={library_games:number;installed_games:number;never_played:number;tracked_seconds:number;imported_seconds:number;last_14_seconds:number;last_30_seconds:number;average_daily_seconds_30d:number;average_weekly_seconds_12w:number;by_provider:{name:string;seconds:number}[];by_platform:{name:string;seconds:number}[];top_games:{name:string;seconds:number}[];monthly:{label:string;seconds:number}[];yearly:{label:string;seconds:number}[]};
type Diagnostic={level:string;area:string;message:string};
type Membership={id:string;name:string;included:boolean};
type SaveBackup={id:string;emulator_id:string|null;source_path:string;backup_path:string;created_at:string};
type UpdateInfo={available:boolean;current_version:string;latest_version:string|null;notes:string|null;published_at:string|null};
type SteamAccountStatus={steam_id:string|null;api_key_configured:boolean;last_sync:string|null;owned_games:number};
type StoreSettings={ggdeals_configured:boolean;itad_configured:boolean;country:string};
type StoreCatalogItem={app_id:string;title:string;cover:string|null;price:number|null;regular:number|null;currency:string|null;discount_percent:number};
type StoreOffer={shop:string;kind:string;price:number;regular:number|null;currency:string;cut:number;url:string};
type StoreComparison={app_id:string;title:string;cover:string|null;offers:StoreOffer[];gg_url:string;note:string};

const app=document.querySelector<HTMLDivElement>('#app')!;
let games:Game[]=[];let selected:string|null=null;let details:Details|null=null;let view='library';let mode:'grid'|'list'='grid';let query='';let filter='all';let sort='title';let libraryLimit=240;
const esc=(v:string)=>v.replace(/[&<>'"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[c]!));
const dur=(s:number)=>{if(!s)return '0 min';const h=Math.floor(s/3600),m=Math.floor((s%3600)/60);return h?`${h}h ${m}min`:`${m}min`};
const date=(v:string|null)=>v?new Date(v).toLocaleString('pt-BR'):'Nunca';
const providerLabel=(id:string)=>({steam:'Steam',epic:'Epic',gog:'GOG',xbox:'Xbox',ea:'EA',ubisoft:'Ubisoft',battlenet:'Battle.net',manual:'Manual',emulation:'Emulação',android:'Android'} as Record<string,string>)[id]||id;
const initials=(title:string)=>title.split(/\s+/).slice(0,2).map(x=>x[0]).join('').toUpperCase();

function shell(){app.innerHTML=`<div class="app-shell"><aside class="rail"><div class="brand"><b>Ludex</b><span>0.9.3</span></div><nav>
${[['library','Biblioteca'],['store','Loja'],['recent','Recentes'],['favorites','Favoritos'],['installed','Instalados'],['collections','Coleções'],['emulation','Emulação'],['stats','Estatísticas'],['settings','Configurações'],['diagnostics','Diagnóstico']].map(([id,n])=>`<button data-view="${id}" class="nav-btn ${view===id?'active':''}">${n}</button>`).join('')}
</nav><div class="privacy">Local-first<br><span>Sem conta obrigatória</span></div></aside><main><header class="topbar"><div><p class="eyebrow">Biblioteca universal</p><h1 id="view-title">Ludex</h1></div><div class="top-actions"><input id="search" value="${esc(query)}" placeholder="Buscar jogos"><button id="add-manual" class="ghost">+ Jogo</button></div></header><div id="content"></div></main></div><div id="modal-root"></div>`;
 document.querySelectorAll<HTMLElement>('[data-view]').forEach(b=>b.onclick=()=>{view=b.dataset.view!;shell();void renderView()});
 document.querySelector<HTMLInputElement>('#search')!.oninput=e=>{query=(e.target as HTMLInputElement).value;libraryLimit=240;if(['library','recent','favorites','installed'].includes(view))renderLibrary()};
 document.querySelector<HTMLButtonElement>('#add-manual')!.onclick=()=>manualModal();
}
function title(t:string){document.querySelector('#view-title')!.textContent=t}
function filteredGames(){let v=games.filter(g=>g.title.toLowerCase().includes(query.toLowerCase()));if(view==='recent')v=v.filter(g=>g.last_played_at).sort((a,b)=>(b.last_played_at||'').localeCompare(a.last_played_at||''));if(view==='favorites'||filter==='favorite')v=v.filter(g=>g.favorite);if(view==='installed'||filter==='installed')v=v.filter(g=>g.installed);if(filter==='uninstalled')v=v.filter(g=>!g.installed);if(filter==='never')v=v.filter(g=>g.total_seconds===0);if(filter==='completed')v=v.filter(g=>['Concluído','100%'].includes(g.status));if(filter.startsWith('provider:'))v=v.filter(g=>g.providers.includes(filter.slice(9)));if(sort==='playtime')v.sort((a,b)=>b.total_seconds-a.total_seconds);else if(sort==='recent')v.sort((a,b)=>(b.last_played_at||'').localeCompare(a.last_played_at||''));else v.sort((a,b)=>a.title.localeCompare(b.title));return v}
function cover(g:Game){return `<div class="cover-art ${g.active?'live':''}" data-cover-id="${g.id}"><span>${esc(initials(g.title))}</span>${g.active?'<b>EM JOGO</b>':''}</div>`}
