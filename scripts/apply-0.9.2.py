from pathlib import Path

def replace_once(path, old, new):
    p=Path(path); text=p.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'missing replacement in {path}: {old[:80]!r}')
    p.write_text(text.replace(old,new,1),encoding='utf-8')

def append_once(path, marker, addition):
    p=Path(path); text=p.read_text(encoding='utf-8')
    if marker in text: return
    p.write_text(text.rstrip()+"\n\n"+addition.strip()+"\n",encoding='utf-8')

# Versions
for path in ['package.json','src-tauri/Cargo.toml','src-tauri/tauri.conf.json']:
    p=Path(path); t=p.read_text(encoding='utf-8').replace('0.9.1','0.9.2'); p.write_text(t,encoding='utf-8')
p=Path('android/app/build.gradle'); t=p.read_text(encoding='utf-8').replace('versionCode 9','versionCode 10').replace('versionName "0.9.0"','versionName "0.9.2"').replace('versionName "0.9.1"','versionName "0.9.2"'); p.write_text(t,encoding='utf-8')

# Steam artwork: modern library cache names + public helper.
replace_once('src-tauri/src/metadata.rs','struct SteamArtwork {\n    cover: Option<String>,\n    hero: Option<String>,\n}','pub(crate) struct SteamArtwork {\n    pub cover: Option<String>,\n    pub hero: Option<String>,\n}')
replace_once('src-tauri/src/metadata.rs','    fn artwork_for(root: &Path, app_id: &str) -> SteamArtwork {','    pub(crate) fn artwork_for(root: &Path, app_id: &str) -> SteamArtwork {')
replace_once('src-tauri/src/metadata.rs','lower.contains("library_600x900")\n                    || lower.contains("portrait")\n                    || lower.contains("capsule_600x900")','lower.contains("library_600x900")\n                    || lower.contains("library_capsule")\n                    || lower.contains("portrait")\n                    || lower.contains("capsule_600x900")')
insert='''\npub(crate) fn steam_artwork_for(app_id: &str) -> SteamArtwork {\n    SteamProvider::detect_root()\n        .map(|root| SteamLocalMetadataProvider::artwork_for(&root, app_id))\n        .unwrap_or_default()\n}\n'''
replace_once('src-tauri/src/metadata.rs','pub fn refresh_local_metadata(connection: &Connection) -> Result<usize, String> {',insert+'\npub fn refresh_local_metadata(connection: &Connection) -> Result<usize, String> {')
replace_once('src-tauri/src/metadata.rs','        fs::write(app.join("library_600x900.jpg"), b"x").unwrap();','        fs::write(app.join("library_capsule.jpg"), b"x").unwrap();')
replace_once('src-tauri/src/metadata.rs','        assert!(files[0].ends_with("library_600x900.jpg"));','        assert!(files[0].ends_with("library_capsule.jpg"));')

# Replace stale single Steam CDN URL with a virtual artwork resolver.
p=Path('src-tauri/src/steam_data.rs'); text=p.read_text(encoding='utf-8')
start=text.index('fn ensure_artwork_fallbacks')
end=text.index('\npub fn sync', start)
new_fn=r'''fn ensure_artwork_fallbacks(connection: &Connection) -> Result<usize, String> {
    let mut statement = connection
        .prepare("SELECT game_id,external_id FROM external_ids WHERE provider='steam'")
        .map_err(|e| e.to_string())?;
    let rows = statement
        .query_map([], |row| Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?)))
        .map_err(|e| e.to_string())?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string())?;
    drop(statement);
    let mut updated = 0usize;
    for (game_id, app_id) in rows {
        if !app_id.chars().all(|c| c.is_ascii_digit()) { continue; }
        let cover = format!("steam-artwork:{app_id}");
        let hero = format!("steam-artwork-hero:{app_id}");
        updated += connection.execute(
            "INSERT INTO game_metadata(game_id,cover,hero,source,manual,updated_at)
             VALUES(?1,?2,?3,'steam-account',0,CURRENT_TIMESTAMP)
             ON CONFLICT(game_id) DO UPDATE SET
               cover=CASE WHEN game_metadata.manual=0 AND (game_metadata.cover IS NULL OR trim(game_metadata.cover)='' OR game_metadata.source='steam-cdn') THEN excluded.cover ELSE game_metadata.cover END,
               hero=CASE WHEN game_metadata.manual=0 AND (game_metadata.hero IS NULL OR trim(game_metadata.hero)='' OR game_metadata.source='steam-cdn') THEN excluded.hero ELSE game_metadata.hero END,
               source=CASE WHEN game_metadata.manual=0 AND game_metadata.source='steam-cdn' THEN excluded.source ELSE game_metadata.source END,
               updated_at=CASE WHEN game_metadata.manual=0 THEN CURRENT_TIMESTAMP ELSE game_metadata.updated_at END",
            params![game_id, cover, hero],
        ).map_err(|e| e.to_string())?;
    }
    Ok(updated)
}

fn steam_store_header(app_id: &str) -> Option<String> {
    let response = reqwest::blocking::Client::builder()
        .timeout(std::time::Duration::from_secs(12))
        .user_agent("Ludex/0.9.2")
        .build().ok()?
        .get("https://store.steampowered.com/api/appdetails")
        .query(&[("appids", app_id), ("cc", "br"), ("l", "brazilian")])
        .send().ok()?;
    if !response.status().is_success() { return None; }
    let value: serde_json::Value = response.json().ok()?;
    value.get(app_id)?.get("data")?.get("header_image")?.as_str().map(str::to_string)
}

pub fn resolve_artwork(value: &str) -> Result<String, String> {
    if value.starts_with("http://") || value.starts_with("https://") || value.starts_with("data:") || value.starts_with("blob:") {
        return Ok(value.to_string());
    }
    let (hero, app_id) = if let Some(v) = value.strip_prefix("steam-artwork-hero:") {
        (true, v)
    } else if let Some(v) = value.strip_prefix("steam-artwork:") {
        (false, v)
    } else {
        return load_local_artwork(value);
    };
    if !app_id.chars().all(|c| c.is_ascii_digit()) { return Err("Steam AppID inválido na artwork".into()); }
    let local = crate::metadata::steam_artwork_for(app_id);
    let candidate = if hero { local.hero.or(local.cover) } else { local.cover.or(local.hero) };
    if let Some(path) = candidate {
        if let Ok(data) = load_local_artwork(&path) { return Ok(data); }
    }
    steam_store_header(app_id).ok_or_else(|| "Artwork da Steam indisponível localmente e no fallback da loja".into())
}
'''
text=text[:start]+new_fn+text[end:]
p.write_text(text,encoding='utf-8')

# Wire modules + commands into Tauri.
p=Path('src-tauri/src/lib.rs'); text=p.read_text(encoding='utf-8')
text=text.replace('mod sessions;\nmod steam_data;\nmod updater;','mod sessions;\nmod steam_data;\nmod steam_account;\nmod store;\nmod secrets;\nmod updater;',1)
text=text.replace('        if provider == "steam" {\n            let _ = steam_data::sync(&c)?;\n        }','        if provider == "steam" {\n            let _ = steam_data::sync(&c)?;\n            if crate::secrets::configured(&c, "steam.web_api_key")? {\n                let _ = steam_account::sync_owned_games(&c);\n            }\n        }',1)
old='''#[tauri::command]
fn load_local_artwork(path: String) -> Result<String, String> {
    steam_data::load_local_artwork(&path)
}
'''
new=r'''#[tauri::command]
fn load_local_artwork(path: String) -> Result<String, String> {
    steam_data::load_local_artwork(&path)
}
#[tauri::command]
fn resolve_artwork(value: String) -> Result<String, String> {
    steam_data::resolve_artwork(&value)
}
#[tauri::command]
fn steam_account_status(state: tauri::State<'_, AppState>) -> Result<steam_account::SteamAccountStatus, String> {
    steam_account::status(&open_product(&state.db_path)?)
}
#[tauri::command]
fn save_steam_web_api_key(state: tauri::State<'_, AppState>, key: String) -> Result<(), String> {
    steam_account::save_api_key(&open_product(&state.db_path)?, &key)
}
#[tauri::command]
async fn sync_steam_account(state: tauri::State<'_, AppState>) -> Result<steam_account::SteamAccountSyncResult, String> {
    let path = state.db_path.clone();
    tauri::async_runtime::spawn_blocking(move || steam_account::sync_owned_games(&open_product(&path)?))
        .await.map_err(|e| e.to_string())?
}
#[tauri::command]
fn store_settings(state: tauri::State<'_, AppState>) -> Result<store::StoreSettingsStatus, String> {
    store::settings(&open_product(&state.db_path)?)
}
#[tauri::command]
fn save_store_settings(state: tauri::State<'_, AppState>, ggdeals_key: Option<String>, itad_key: Option<String>, country: String) -> Result<(), String> {
    store::save_keys(&open_product(&state.db_path)?, ggdeals_key.as_deref(), itad_key.as_deref(), &country)
}
#[tauri::command]
async fn store_catalog(state: tauri::State<'_, AppState>) -> Result<Vec<store::StoreCatalogItem>, String> {
    let path = state.db_path.clone();
    tauri::async_runtime::spawn_blocking(move || store::catalog(&open_product(&path)?)).await.map_err(|e| e.to_string())?
}
#[tauri::command]
async fn store_compare(state: tauri::State<'_, AppState>, app_id: String) -> Result<store::StoreComparison, String> {
    let path = state.db_path.clone();
    tauri::async_runtime::spawn_blocking(move || store::compare(&open_product(&path)?, &app_id)).await.map_err(|e| e.to_string())?
}
#[tauri::command]
fn open_external_url(url: String) -> Result<(), String> {
    let allowed = ["https://store.steampowered.com/", "https://steamcommunity.com/", "https://gg.deals/", "https://isthereanydeal.com/", "https://docs.isthereanydeal.com/"];
    if !allowed.iter().any(|prefix| url.starts_with(prefix)) { return Err("Domínio externo não permitido".into()); }
    open_uri(&url)
}
'''
if old not in text: raise SystemExit('lib artwork command marker missing')
text=text.replace(old,new,1)
text=text.replace('            load_local_artwork,\n            check_for_updates,','            load_local_artwork,\n            resolve_artwork,\n            steam_account_status,\n            save_steam_web_api_key,\n            sync_steam_account,\n            store_settings,\n            save_store_settings,\n            store_catalog,\n            store_compare,\n            open_external_url,\n            check_for_updates,',1)
p.write_text(text,encoding='utf-8')

# Frontend types, nav, artwork resolver and Store page.
p=Path('src/main.ts'); text=p.read_text(encoding='utf-8')
text=text.replace("type UpdateInfo={available:boolean;current_version:string;latest_version:string|null;notes:string|null;published_at:string|null};",'''type UpdateInfo={available:boolean;current_version:string;latest_version:string|null;notes:string|null;published_at:string|null};
type SteamAccountStatus={steam_id:string|null;api_key_configured:boolean;last_sync:string|null;owned_games:number};
type StoreSettings={ggdeals_configured:boolean;itad_configured:boolean;country:string};
type StoreCatalogItem={app_id:string;title:string;cover:string|null;price:number|null;regular:number|null;currency:string|null;discount_percent:number};
type StoreOffer={shop:string;kind:string;price:number;regular:number|null;currency:string;cut:number;url:string};
type StoreComparison={app_id:string;title:string;cover:string|null;offers:StoreOffer[];gg_url:string;note:string};''',1)
text=text.replace('<div class="brand"><b>Ludex</b><span>0.9</span></div>','<div class="brand"><b>Ludex</b><span>0.9.2</span></div>',1)
text=text.replace("[['library','Biblioteca'],['recent','Recentes']","[['library','Biblioteca'],['store','Loja'],['recent','Recentes']",1)
text=text.replace("async function artworkSrc(value:string){if(/^(https?:|data:|blob:)/i.test(value))return value;return invoke<string>('load_local_artwork',{path:value})}","async function artworkSrc(value:string){if(/^(https?:|data:|blob:)/i.test(value))return value;return invoke<string>('resolve_artwork',{value})}",1)
store_fn=r'''
const money=(value:number,currency='BRL')=>new Intl.NumberFormat('pt-BR',{style:'currency',currency}).format(value);
async function renderStore(){
 title('Loja');const content=document.querySelector('#content')!;content.innerHTML='<section class="page"><div class="store-loading">Carregando ofertas…</div></section>';
 try{const items=await invoke<StoreCatalogItem[]>('store_catalog');content.innerHTML=`<section class="page"><div class="page-actions"><div><h2>Jogos e ofertas</h2><p>Catálogo da Steam com comparação de preços para o Brasil. Clique em um jogo para consultar as fontes configuradas.</p></div><button id="store-config" class="ghost">Configurar fontes</button></div><div class="store-grid">${items.map(i=>`<button class="store-card" data-store-app="${i.app_id}"><div class="store-cover" data-store-cover="${i.app_id}">${i.cover?`<img src="${esc(i.cover)}" loading="lazy" onerror="this.remove()">`:''}</div><strong>${esc(i.title)}</strong><span>${i.price!==null?money(i.price,i.currency||'BRL'):'Comparar preços'}</span>${i.discount_percent>0?`<b>-${i.discount_percent}%</b>`:''}</button>`).join('')}</div><div id="store-detail" class="store-detail"><p class="muted">Selecione um jogo para ver onde está mais barato.</p></div></section>`;
 (document.querySelector('#store-config') as HTMLButtonElement).onclick=()=>{view='settings';shell();void renderSettings()};
 document.querySelectorAll<HTMLElement>('[data-store-app]').forEach(b=>b.onclick=()=>void renderStoreComparison(b.dataset.storeApp!));
 document.querySelectorAll<HTMLImageElement>('.store-cover img').forEach(img=>{const raw=img.getAttribute('src')||'';if(raw.startsWith('steam-artwork:'))void artworkSrc(raw).then(v=>img.src=v).catch(()=>img.remove())});
 }catch(e){content.innerHTML=`<section class="page"><div class="empty">Não foi possível carregar a loja: ${esc(String(e))}</div></section>`}
}
async function renderStoreComparison(appId:string){const pane=document.querySelector('#store-detail')!;pane.innerHTML='<p class="muted">Consultando Steam, lojas oficiais e keyshops…</p>';try{const c=await invoke<StoreComparison>('store_compare',{appId});pane.innerHTML=`<div class="store-detail-head"><div><h2>${esc(c.title)}</h2><p>Steam AppID ${esc(c.app_id)}</p></div><button id="gg-link" class="ghost">Ver todas no GG.deals</button></div><div class="offer-list">${c.offers.map((o,i)=>`<button data-offer="${i}" class="offer-row ${i===0?'best':''}"><span><b>${esc(o.shop)}</b><small>${o.kind==='keyshop'?'Keyshop':'Loja oficial'}${o.cut?` · -${o.cut}%`:''}</small></span><strong>${money(o.price,o.currency)}</strong>${o.regular&&o.regular>o.price?`<del>${money(o.regular,o.currency)}</del>`:''}</button>`).join('')||'<p class="empty">Nenhuma fonte de preço configurada respondeu para este jogo.</p>'}</div><p class="store-note">${esc(c.note)}</p>`;document.querySelectorAll<HTMLElement>('[data-offer]').forEach(b=>b.onclick=()=>{const o=c.offers[Number(b.dataset.offer)];if(o)void invoke('open_external_url',{url:o.url})});(document.querySelector('#gg-link') as HTMLButtonElement).onclick=()=>void invoke('open_external_url',{url:c.gg_url});}catch(e){pane.innerHTML=`<p class="empty">${esc(String(e))}</p>`}}
'''
text=text.replace('async function renderCollections(){',store_fn+'\nasync function renderCollections(){',1)
# Replace settings function with extended version by inserting panels after content HTML starts.
text=text.replace("async function renderSettings(){title('Configurações');const ps=await invoke<Provider[]>('provider_statuses');let update:UpdateInfo|null=null;try{update=await invoke<UpdateInfo>('check_for_updates')}catch{}", "async function renderSettings(){title('Configurações');const ps=await invoke<Provider[]>('provider_statuses');let update:UpdateInfo|null=null;let steamAccount:SteamAccountStatus|null=null;let storeSettings:StoreSettings|null=null;try{update=await invoke<UpdateInfo>('check_for_updates')}catch{}try{steamAccount=await invoke<SteamAccountStatus>('steam_account_status')}catch{}try{storeSettings=await invoke<StoreSettings>('store_settings')}catch{}",1)
needle='<h2>Providers</h2><div class="provider-grid">'
extra='''<h2>Conta Steam</h2><div class="settings-panels"><article><h3>Biblioteca completa</h3><p>Conta detectada: <b>${esc(steamAccount?.steam_id||'não detectada')}</b> · ${steamAccount?.owned_games||0} jogos Steam conhecidos.</p><p class="muted">Para importar também os jogos não instalados, configure uma Steam Web API key. Ela fica protegida pelo DPAPI do seu usuário do Windows.</p><label>Steam Web API key<input id="steam-api-key" type="password" placeholder="Cole a chave; deixe vazio para manter a atual"></label><div><button id="save-steam-key" class="ghost">Salvar chave</button><button id="sync-steam-account" class="primary" ${steamAccount?.api_key_configured?'':'disabled'}>Importar biblioteca completa</button><button id="steam-key-help" class="text-btn">Obter chave</button></div><small>Último sync da conta: ${date(steamAccount?.last_sync||null)}</small></article><article><h3>Fontes da Loja</h3><p>Steam funciona sem chave. ITAD adiciona lojas oficiais individuais (incluindo Nuuvem quando disponível no Brasil); GG.deals adiciona o menor preço entre lojas oficiais e keyshops.</p><label>GG.deals API key<input id="gg-key" type="password" placeholder="${storeSettings?.ggdeals_configured?'Configurada · cole para substituir':'Opcional'}"></label><label>IsThereAnyDeal API key<input id="itad-key" type="password" placeholder="${storeSettings?.itad_configured?'Configurada · cole para substituir':'Opcional'}"></label><label>País<input id="store-country" value="${esc(storeSettings?.country||'BR')}" maxlength="2"></label><button id="save-store-keys" class="ghost">Salvar fontes da Loja</button><p class="muted">Eneba e Instant Gaming não oferecem uma API pública de preços para consumidores; o Ludex não faz scraping. Quando cobertas pelo GG.deals, entram no menor preço de keyshops e no link detalhado.</p></article></div><h2>Providers</h2><div class="provider-grid">'''
if needle not in text: raise SystemExit('settings providers marker missing')
text=text.replace(needle,extra,1)
# Inject settings handlers before export-json handler.
handler_marker=";(document.querySelector('#export-json') as HTMLButtonElement).onclick=async()=>"
handlers=r''';const saveSteam=document.querySelector<HTMLButtonElement>('#save-steam-key');if(saveSteam)saveSteam.onclick=async()=>{const key=(document.querySelector('#steam-api-key') as HTMLInputElement).value;try{await invoke('save_steam_web_api_key',{key});toast('Steam Web API key salva com proteção do Windows.');void renderSettings()}catch(e){toast(String(e),true)}};const syncAccount=document.querySelector<HTMLButtonElement>('#sync-steam-account');if(syncAccount)syncAccount.onclick=async()=>{syncAccount.disabled=true;syncAccount.textContent='Importando…';try{const r=await invoke<any>('sync_steam_account');toast(`${r.games_found} jogos encontrados · ${r.games_created} novos.`);await refresh();void renderSettings()}catch(e){toast(String(e),true);syncAccount.disabled=false;syncAccount.textContent='Importar biblioteca completa'}};const help=document.querySelector<HTMLButtonElement>('#steam-key-help');if(help)help.onclick=()=>void invoke('open_external_url',{url:'https://steamcommunity.com/dev/apikey'});const storeSave=document.querySelector<HTMLButtonElement>('#save-store-keys');if(storeSave)storeSave.onclick=async()=>{const gg=(document.querySelector('#gg-key') as HTMLInputElement).value.trim();const itad=(document.querySelector('#itad-key') as HTMLInputElement).value.trim();const country=(document.querySelector('#store-country') as HTMLInputElement).value.trim();try{await invoke('save_store_settings',{ggdealsKey:gg||null,itadKey:itad||null,country});toast('Fontes da Loja salvas.');void renderSettings()}catch(e){toast(String(e),true)}}'''
if handler_marker not in text: raise SystemExit('settings handler marker missing')
text=text.replace(handler_marker,handlers+handler_marker,1)
text=text.replace("else if(view==='collections')await renderCollections();","else if(view==='store')await renderStore();else if(view==='collections')await renderCollections();",1)
p.write_text(text,encoding='utf-8')

append_once('src/styles.css','.store-grid{',r'''
.store-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(170px,1fr));gap:14px}.store-card{position:relative;border:1px solid #1c2737;background:#0f151f;color:white;border-radius:13px;padding:0 0 12px;text-align:left;overflow:hidden;cursor:pointer}.store-card:hover{transform:translateY(-2px);border-color:#39547a}.store-cover{aspect-ratio:460/215;background:linear-gradient(145deg,#25354d,#111823);overflow:hidden}.store-cover img{width:100%;height:100%;object-fit:cover}.store-card strong,.store-card span{display:block;padding:0 12px}.store-card strong{margin-top:10px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.store-card span{margin-top:5px;color:#8fb7ff}.store-card>b{position:absolute;right:8px;top:8px;background:#74e3ac;color:#062014;padding:5px 7px;border-radius:7px;font-size:11px}.store-detail{margin-top:24px;background:#0f151f;border:1px solid #192231;border-radius:15px;padding:18px}.store-detail-head{display:flex;justify-content:space-between;align-items:center;gap:16px}.store-detail-head h2{margin:0}.store-detail-head p,.store-note{color:#718197;font-size:12px}.offer-list{display:grid;gap:8px;margin-top:14px}.offer-row{display:grid;grid-template-columns:minmax(0,1fr) auto auto;gap:15px;align-items:center;width:100%;background:#111923;border:1px solid #1d2939;color:white;border-radius:10px;padding:12px;text-align:left;cursor:pointer}.offer-row.best{border-color:#5d8bd0;background:#142034}.offer-row small{display:block;color:#728196;margin-top:3px}.offer-row strong{font-size:16px}.offer-row del{color:#657386;font-size:11px}.settings-panels label{display:grid;gap:6px;color:#8392a5;font-size:11px;margin:10px 0}.store-loading{padding:40px;color:#8492a6}
''')

# README release note.
p=Path('README.md'); text=p.read_text(encoding='utf-8')
if '## 0.9.2' not in text:
    text += '''\n\n## 0.9.2\n\n- Steam: corrige artwork moderno (`library_capsule`) e adiciona resolver com fallback seguro.\n- Steam Account: importação opcional da biblioteca completa via `IPlayerService/GetOwnedGames`, incluindo jogos não instalados e playtime histórico. A chave é fornecida pelo usuário e protegida com DPAPI no Windows.\n- Loja: catálogo Steam para o Brasil, preço Steam, ofertas individuais de lojas oficiais via IsThereAnyDeal e melhor preço de lojas/keyshops via GG.deals. Sem scraping de marketplaces.\n- Eneba/Instant Gaming: APIs públicas de consumidor não estão disponíveis; quando cobertas pelo GG.deals entram no comparador agregado e no link detalhado.\n'''
p.write_text(text,encoding='utf-8')
