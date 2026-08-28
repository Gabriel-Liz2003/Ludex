from pathlib import Path

lib = Path('src-tauri/src/lib.rs')
t = lib.read_text(encoding='utf-8')
t = t.replace('mod identity;\nmod models;', 'mod identity;\nmod metadata;\nmod models;', 1)
t = t.replace(
    'CollectionRecord, DiagnosticItem, EmulatorRecord, LibraryStats, MetadataRecord, RomRecord,\n    RomScanResult, SyncSummary,',
    'CollectionMembership, CollectionRecord, DiagnosticItem, EmulatorRecord, LibraryStats, MetadataRecord,\n    RomRecord, RomScanResult, SaveBackupRecord, SyncSummary,',
    1,
)
old = '''        let c = open_product(&path)?;
        product::sync_provider(&c, &provider, &scan.installations, &root)
'''
new = '''        let c = open_product(&path)?;
        let result = product::sync_provider(&c, &provider, &scan.installations, &root)?;
        if provider == "steam" {
            let _ = metadata::refresh_local_metadata(&c);
        }
        Ok(result)
'''
if old in t:
    t = t.replace(old, new, 1)

marker = '''#[tauri::command]
fn list_collections(state: tauri::State<'_, AppState>) -> Result<Vec<CollectionRecord>, String> {
'''
commands = '''#[tauri::command]
fn collection_memberships(
    state: tauri::State<'_, AppState>,
    game_id: String,
) -> Result<Vec<CollectionMembership>, String> {
    product::collection_memberships(&open_product(&state.db_path)?, &game_id)
}

'''
if 'fn collection_memberships(' not in t:
    if marker not in t:
        raise SystemExit('collection marker not found')
    t = t.replace(marker, commands + marker, 1)

marker = '''#[tauri::command]
fn diagnostics(state: tauri::State<'_, AppState>) -> Result<Vec<DiagnosticItem>, String> {
'''
commands = '''#[tauri::command]
fn refresh_local_metadata(state: tauri::State<'_, AppState>) -> Result<usize, String> {
    metadata::refresh_local_metadata(&open_product(&state.db_path)?)
}
#[tauri::command]
fn backup_save_path(
    state: tauri::State<'_, AppState>,
    source: String,
    emulator_id: Option<String>,
) -> Result<SaveBackupRecord, String> {
    product::backup_save_path(
        &open_product(&state.db_path)?,
        &source,
        &state.data_dir.join("save-backups"),
        emulator_id.as_deref(),
    )
}
#[tauri::command]
fn list_save_backups(state: tauri::State<'_, AppState>) -> Result<Vec<SaveBackupRecord>, String> {
    product::save_backups(&open_product(&state.db_path)?)
}
#[tauri::command]
fn restore_save_backup(
    state: tauri::State<'_, AppState>,
    backup_id: String,
) -> Result<(), String> {
    product::restore_save_backup(&open_product(&state.db_path)?, &backup_id)
}

'''
if 'fn backup_save_path(' not in t:
    if marker not in t:
        raise SystemExit('diagnostics marker not found')
    t = t.replace(marker, commands + marker, 1)

handler_start = t.find('generate_handler![')
handler_text = t[handler_start:]
for command, anchor in [
    ('collection_memberships,', 'list_collections,'),
    ('refresh_local_metadata,', 'backup_database,'),
    ('backup_save_path,\n            list_save_backups,\n            restore_save_backup,', 'refresh_local_metadata,'),
]:
    name = command.split(',')[0]
    if name not in handler_text:
        if anchor not in t:
            raise SystemExit(f'invoke anchor missing: {anchor}')
        t = t.replace(anchor, command + '\n            ' + anchor, 1)
        handler_text = t[t.find('generate_handler!['):]
lib.write_text(t, encoding='utf-8')

ui = Path('src/main.ts')
s = ui.read_text(encoding='utf-8')
if 'type Membership=' not in s:
    s = s.replace(
        'type Diagnostic={level:string;area:string;message:string};',
        'type Diagnostic={level:string;area:string;message:string};\ntype Membership={id:string;name:string;included:boolean};\ntype SaveBackup={id:string;emulator_id:string|null;source_path:string;backup_path:string;created_at:string};',
        1,
    )
old = "const g=details.game;let meta:Metadata|null=null;try{meta=await invoke('get_metadata',{gameId:id})}catch{}const playable="
new = "const g=details.game;let meta:Metadata|null=null;let memberships:Membership[]=[];try{meta=await invoke('get_metadata',{gameId:id})}catch{}try{memberships=await invoke('collection_memberships',{gameId:id})}catch{}const playable="
if old in s:
    s = s.replace(old, new, 1)
old = '''</div><div class="sessions"><h3>Sessões recentes</h3>${details.recent_sessions.map'''
new = '''</div><div class="collections-inline"><h3>Coleções</h3><div class="chips">${memberships.map(c=>`<button data-membership="${c.id}" class="${c.included?'active':''}">${c.included?'✓ ':''}${esc(c.name)}</button>`).join('')||'<small>Nenhuma coleção criada.</small>'}</div></div><div class="sessions"><h3>Sessões recentes</h3>${details.recent_sessions.map'''
if old in s:
    s = s.replace(old, new, 1)
old = '''(document.querySelector('#edit-meta') as HTMLButtonElement).onclick=()=>metadataModal(g,meta);
}'''
new = '''(document.querySelector('#edit-meta') as HTMLButtonElement).onclick=()=>metadataModal(g,meta);document.querySelectorAll<HTMLElement>('[data-membership]').forEach(b=>b.onclick=async()=>{const item=memberships.find(x=>x.id===b.dataset.membership);if(!item)return;await invoke('set_collection_game',{collectionId:item.id,gameId:id,add:!item.included});await selectGame(id,false)});
}'''
if old in s:
    s = s.replace(old, new, 1)

old = "async function renderEmulation(){title('Emulação');const [emus,roms]=await Promise.all([invoke<Emulator[]>('list_emulators'),invoke<Rom[]>('list_roms')]);"
new = "async function renderEmulation(){title('Emulação');const [emus,roms,backups]=await Promise.all([invoke<Emulator[]>('list_emulators'),invoke<Rom[]>('list_roms'),invoke<SaveBackup[]>('list_save_backups')]);"
if old in s:
    s = s.replace(old, new, 1)
old = '''<small>${esc(e.executable)}</small></article>`).join('')||'<div class="empty">Nenhum emulador configurado.</div>'}</div><div class="page-actions"><h2>ROMs'''
new = '''<small>${esc(e.executable)}</small>${e.saves_directory?`<button data-save-backup="${e.id}" class="ghost">Backup de saves</button>`:''}</article>`).join('')||'<div class="empty">Nenhum emulador configurado.</div>'}</div>${backups.length?`<div class="page-actions"><h2>Backups de saves <span class="count">${backups.length}</span></h2></div><div class="rom-list">${backups.slice(0,12).map(b=>`<div><span><b>${esc(b.source_path)}</b><small>${date(b.created_at)} · ${esc(b.backup_path)}</small></span><button data-restore-backup="${b.id}" class="ghost">Restaurar</button></div>`).join('')}</div>`:''}<div class="page-actions"><h2>ROMs'''
if old in s:
    s = s.replace(old, new, 1)
old = '''(document.querySelector('#new-emu') as HTMLButtonElement).onclick=()=>emulatorModal();(document.querySelector('#scan-rom') as HTMLButtonElement).onclick=()=>romScanModal(emus);document.querySelectorAll<HTMLElement>('[data-rom]').forEach'''
new = '''(document.querySelector('#new-emu') as HTMLButtonElement).onclick=()=>emulatorModal();(document.querySelector('#scan-rom') as HTMLButtonElement).onclick=()=>romScanModal(emus);document.querySelectorAll<HTMLElement>('[data-save-backup]').forEach(b=>b.onclick=async()=>{const e=emus.find(x=>x.id===b.dataset.saveBackup);if(!e?.saves_directory)return;try{await invoke('backup_save_path',{source:e.saves_directory,emulatorId:e.id});toast('Backup de saves criado.');void renderEmulation()}catch(err){toast(String(err),true)}});document.querySelectorAll<HTMLElement>('[data-restore-backup]').forEach(b=>b.onclick=async()=>{if(!confirm('Restaurar este backup? O Ludex recusa sobrescrever um destino existente.'))return;try{await invoke('restore_save_backup',{backupId:b.dataset.restoreBackup});toast('Backup restaurado.')}catch(err){toast(String(err),true)}});document.querySelectorAll<HTMLElement>('[data-rom]').forEach'''
if old in s:
    s = s.replace(old, new, 1)
ui.write_text(s, encoding='utf-8')
