from pathlib import Path

p=Path('src-tauri/src/product_models.rs')
t=p.read_text(encoding='utf-8')
insert='''
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SaveBackupRecord { pub id:String, pub emulator_id:Option<String>, pub source_path:String, pub backup_path:String, pub created_at:String }

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CollectionMembership { pub id:String, pub name:String, pub included:bool }
'''
if 'pub struct SaveBackupRecord' not in t:t+=insert
p.write_text(t,encoding='utf-8')

p=Path('src-tauri/src/product.rs');t=p.read_text(encoding='utf-8')
# Add migration version 3 marker/indexes.
old='''    c.execute_batch("CREATE UNIQUE INDEX IF NOT EXISTS idx_rom_hash ON roms(hash_sha256) WHERE hash_sha256 IS NOT NULL; INSERT OR IGNORE INTO schema_migrations(version) VALUES(2);").map_err(|e|e.to_string())?;
    Ok(())'''
new='''    c.execute_batch("CREATE UNIQUE INDEX IF NOT EXISTS idx_rom_hash ON roms(hash_sha256) WHERE hash_sha256 IS NOT NULL; INSERT OR IGNORE INTO schema_migrations(version) VALUES(2); CREATE INDEX IF NOT EXISTS idx_games_updated ON games(updated_at); CREATE INDEX IF NOT EXISTS idx_sessions_started ON play_sessions(started_at); CREATE INDEX IF NOT EXISTS idx_installations_provider_installed ON installations(provider,installed); INSERT OR IGNORE INTO schema_migrations(version) VALUES(3);").map_err(|e|e.to_string())?;
    Ok(())'''
if old in t:t=t.replace(old,new)

# Collection membership getter.
needle='pub fn merge_games('
idx=t.find(needle)
if idx>=0 and 'pub fn collection_memberships' not in t:
    add='''pub fn collection_memberships(c:&Connection, game_id:&str)->Result<Vec<CollectionMembership>,String>{
    let mut s=c.prepare("SELECT c.id,c.name,EXISTS(SELECT 1 FROM collection_games cg WHERE cg.collection_id=c.id AND cg.game_id=?1) FROM collections c ORDER BY c.name COLLATE NOCASE").map_err(|e|e.to_string())?;
    let result=s.query_map([game_id],|r|Ok(CollectionMembership{id:r.get(0)?,name:r.get(1)?,included:r.get::<_,i64>(2)?!=0})).map_err(|e|e.to_string())?.collect::<Result<Vec<_>,_>>().map_err(|e|e.to_string()); result
}

'''
    t=t[:idx]+add+t[idx:]

# Replace backup_json block through query_json marker.
start=t.find('pub fn backup_json('); end=t.find('fn query_json(',start)
if start>=0 and end>=0:
    replacement='''pub fn backup_json(c:&Connection)->Result<String,String>{
    let table=|sql:&str|->Result<serde_json::Value,String>{serde_json::from_str(&query_json(c,sql)?).map_err(|e|e.to_string())};
    let data=serde_json::json!({
      "games":table("SELECT id,title,platform,source,favorite,status,normalized_title,created_at,updated_at FROM games")?,
      "installations":table("SELECT id,game_id,source,provider,external_id,executable,install_dir,working_dir,launch_args,installed,updated_at FROM installations")?,
      "sessions":table("SELECT id,game_id,installation_id,started_at,ended_at,duration_seconds,device,provider,recovered FROM play_sessions WHERE ended_at IS NOT NULL")?,
      "metadata":table("SELECT game_id,description,developer,publisher,release_date,genres,cover,hero,source,manual,updated_at FROM game_metadata")?,
      "collections":table("SELECT id,name,kind,filter_json,updated_at FROM collections")?,
      "collection_games":table("SELECT collection_id,game_id FROM collection_games")?,
      "imported_playtime":table("SELECT game_id,provider,seconds,updated_at FROM imported_playtime")?,
      "achievements":table("SELECT id,game_id,provider,external_id,name,description,unlocked,unlocked_at,icon,progress_current,progress_target,rarity FROM achievements")?,
      "emulators":table("SELECT id,name,platform,executable,arguments_template,rom_directory,bios_directory,saves_directory,extensions,core,enabled FROM emulators")?,
      "roms":table("SELECT id,game_id,platform,path,emulator_id,hash_sha256,size_bytes,launch_args,core,updated_at FROM roms")?
    });
    serde_json::to_string_pretty(&BackupEnvelope{format:"ludex-backup".into(),version:1,exported_at:Utc::now().to_rfc3339(),data}).map_err(|e|e.to_string())
}

'''
    t=t[:start]+replacement+t[end:]

# Replace import_sync_json through diagnostics marker.
start=t.find('pub fn import_sync_json('); end=t.find('pub fn diagnostics(',start)
if start>=0 and end>=0:
    replacement=r'''fn json_str<'a>(v:&'a serde_json::Value,key:&str)->Option<&'a str>{v.get(key).and_then(|x|x.as_str())}
fn ms_rfc3339(ms:i64)->Option<String>{chrono::DateTime::<Utc>::from_timestamp_millis(ms).map(|d|d.to_rfc3339())}
fn remote_is_newer(local:Option<String>,remote:Option<&str>)->bool{match(local,remote){(None,_)=>true,(Some(_),None)=>false,(Some(l),Some(r))=>r>l.as_str()}}

pub fn import_sync_json(c:&mut Connection,json:&str)->Result<SyncSummary,String>{
    let root:serde_json::Value=serde_json::from_str(json).map_err(|e|format!("Sync inválido: {e}"))?;
    let format=json_str(&root,"format").unwrap_or(""); let version=root.get("version").and_then(|v|v.as_u64()).unwrap_or(0);
    if version!=1||!(format=="ludex-backup"||format=="ludex-mobile-sync"){return Err("Formato/versão de sync não suportado".into())}
    let data=if format=="ludex-backup"{root.get("data").cloned().unwrap_or_default()}else{root.clone()};
    let mut out=SyncSummary{inserted:0,updated:0,skipped:0}; let tx=c.transaction().map_err(|e|e.to_string())?;
    if let Some(items)=data.get("games").and_then(|v|v.as_array()){for g in items{
      let id=json_str(g,"id").unwrap_or("");let title=json_str(g,"title").unwrap_or("");if id.is_empty()||title.is_empty(){out.skipped+=1;continue}
      let local:Option<String>=tx.query_row("SELECT updated_at FROM games WHERE id=?1",[id],|r|r.get(0)).optional().map_err(|e|e.to_string())?;
      let remote_owned=g.get("updated_at_ms").and_then(|v|v.as_i64()).and_then(ms_rfc3339);let remote=json_str(g,"updated_at").or(remote_owned.as_deref());
      if local.is_none(){tx.execute("INSERT INTO games(id,title,platform,source,favorite,status,normalized_title,created_at,updated_at) VALUES(?1,?2,?3,?4,?5,?6,?7,CURRENT_TIMESTAMP,COALESCE(?8,CURRENT_TIMESTAMP))",params![id,title,json_str(g,"platform").unwrap_or("PC"),json_str(g,"source").unwrap_or("sync"),g.get("favorite").and_then(|v|v.as_i64()).unwrap_or(0),json_str(g,"status").unwrap_or("Quero jogar"),normalize_title(title),remote]).map_err(|e|e.to_string())?;out.inserted+=1}
      else if remote_is_newer(local.clone(),remote){tx.execute("UPDATE games SET title=?1,platform=?2,favorite=?3,status=?4,updated_at=COALESCE(?5,updated_at) WHERE id=?6",params![title,json_str(g,"platform").unwrap_or("PC"),g.get("favorite").and_then(|v|v.as_i64()).unwrap_or(0),json_str(g,"status").unwrap_or("Quero jogar"),remote,id]).map_err(|e|e.to_string())?;out.updated+=1}else{out.skipped+=1}
    }}
    if let Some(items)=data.get("installations").and_then(|v|v.as_array()){for x in items{let id=json_str(x,"id").unwrap_or("");let game=json_str(x,"game_id").unwrap_or("");if id.is_empty()||game.is_empty(){continue}tx.execute("INSERT OR IGNORE INTO installations(id,game_id,source,provider,external_id,executable,install_dir,working_dir,launch_args,installed,updated_at) VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,COALESCE(?11,CURRENT_TIMESTAMP))",params![id,game,json_str(x,"source").unwrap_or("sync"),json_str(x,"provider").unwrap_or("sync"),json_str(x,"external_id"),json_str(x,"executable"),json_str(x,"install_dir"),json_str(x,"working_dir"),json_str(x,"launch_args"),x.get("installed").and_then(|v|v.as_i64()).unwrap_or(0),json_str(x,"updated_at")]).map_err(|e|e.to_string())?;}}
    if let Some(items)=data.get("sessions").and_then(|v|v.as_array()){for s in items{let id=json_str(s,"id").unwrap_or("");let game=json_str(s,"game_id").unwrap_or("");if id.is_empty()||game.is_empty(){continue}let start_owned=s.get("started_at_ms").and_then(|v|v.as_i64()).and_then(ms_rfc3339);let end_owned=s.get("ended_at_ms").and_then(|v|v.as_i64()).and_then(ms_rfc3339);let start=json_str(s,"started_at").or(start_owned.as_deref());let end=json_str(s,"ended_at").or(end_owned.as_deref());if let(Some(st),Some(en))=(start,end){tx.execute("INSERT OR IGNORE INTO play_sessions(id,game_id,installation_id,started_at,ended_at,duration_seconds,device,provider,recovered) VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9)",params![id,game,json_str(s,"installation_id"),st,en,s.get("duration_seconds").and_then(|v|v.as_i64()).unwrap_or(0),json_str(s,"device").unwrap_or("sync"),json_str(s,"provider").unwrap_or("sync"),s.get("recovered").and_then(|v|v.as_i64()).unwrap_or(0)]).map_err(|e|e.to_string())?;}}
    }}
    if let Some(items)=data.get("metadata").and_then(|v|v.as_array()){for m in items{let game=json_str(m,"game_id").unwrap_or("");if game.is_empty(){continue}let manual=m.get("manual").and_then(|v|v.as_i64()).unwrap_or(0);let existing_manual:i64=tx.query_row("SELECT COALESCE((SELECT manual FROM game_metadata WHERE game_id=?1),0)",[game],|r|r.get(0)).map_err(|e|e.to_string())?;if existing_manual!=0&&manual==0{continue}tx.execute("INSERT INTO game_metadata(game_id,description,developer,publisher,release_date,genres,cover,hero,source,manual,updated_at) VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,COALESCE(?11,CURRENT_TIMESTAMP)) ON CONFLICT(game_id) DO UPDATE SET description=excluded.description,developer=excluded.developer,publisher=excluded.publisher,release_date=excluded.release_date,genres=excluded.genres,cover=excluded.cover,hero=excluded.hero,source=excluded.source,manual=excluded.manual,updated_at=excluded.updated_at",params![game,json_str(m,"description"),json_str(m,"developer"),json_str(m,"publisher"),json_str(m,"release_date"),json_str(m,"genres"),json_str(m,"cover"),json_str(m,"hero"),json_str(m,"source").unwrap_or("sync"),manual,json_str(m,"updated_at")]).map_err(|e|e.to_string())?;}}
    if let Some(items)=data.get("collections").and_then(|v|v.as_array()){for x in items{if let(Some(id),Some(name))=(json_str(x,"id"),json_str(x,"name")){tx.execute("INSERT OR IGNORE INTO collections(id,name,kind,filter_json,updated_at) VALUES(?1,?2,?3,?4,COALESCE(?5,CURRENT_TIMESTAMP))",params![id,name,json_str(x,"kind").unwrap_or("manual"),json_str(x,"filter_json"),json_str(x,"updated_at")]).map_err(|e|e.to_string())?;}}}
    if let Some(items)=data.get("collection_games").and_then(|v|v.as_array()){for x in items{if let(Some(col),Some(game))=(json_str(x,"collection_id"),json_str(x,"game_id")){tx.execute("INSERT OR IGNORE INTO collection_games(collection_id,game_id) VALUES(?1,?2)",params![col,game]).map_err(|e|e.to_string())?;}}}
    if let Some(items)=data.get("imported_playtime").and_then(|v|v.as_array()){for x in items{if let(Some(game),Some(provider))=(json_str(x,"game_id"),json_str(x,"provider")){tx.execute("INSERT INTO imported_playtime(game_id,provider,seconds,updated_at) VALUES(?1,?2,?3,COALESCE(?4,CURRENT_TIMESTAMP)) ON CONFLICT(game_id,provider) DO UPDATE SET seconds=MAX(imported_playtime.seconds,excluded.seconds),updated_at=excluded.updated_at",params![game,provider,x.get("seconds").and_then(|v|v.as_i64()).unwrap_or(0),json_str(x,"updated_at")]).map_err(|e|e.to_string())?;}}}
    tx.commit().map_err(|e|e.to_string())?;Ok(out)
}

'''
    t=t[:start]+replacement+t[end:]

# Save backup functions before diagnostics.
idx=t.find('pub fn diagnostics(')
if idx>=0 and 'pub fn backup_save_path' not in t:
    add=r'''fn copy_tree(src:&Path,dst:&Path)->Result<(),String>{if src.is_file(){if let Some(p)=dst.parent(){fs::create_dir_all(p).map_err(|e|e.to_string())?}fs::copy(src,dst).map_err(|e|e.to_string())?;return Ok(())}fs::create_dir_all(dst).map_err(|e|e.to_string())?;for e in fs::read_dir(src).map_err(|e|e.to_string())?.flatten(){let p=e.path();copy_tree(&p,&dst.join(e.file_name()))?}Ok(())}
pub fn backup_save_path(c:&Connection,source:&str,backup_root:&Path,emulator_id:Option<&str>)->Result<SaveBackupRecord,String>{let src=Path::new(source);if !src.exists(){return Err("Caminho de save não encontrado".into())}fs::create_dir_all(backup_root).map_err(|e|e.to_string())?;let id=Uuid::new_v4().to_string();let name=src.file_name().and_then(|x|x.to_str()).unwrap_or("save");let dst=backup_root.join(format!("{}-{}",Utc::now().format("%Y%m%d-%H%M%S"),name));copy_tree(src,&dst)?;c.execute("INSERT INTO save_backups(id,emulator_id,source_path,backup_path) VALUES(?1,?2,?3,?4)",params![id,emulator_id,source,dst.to_string_lossy()]).map_err(|e|e.to_string())?;let created_at=c.query_row("SELECT created_at FROM save_backups WHERE id=?1",[&id],|r|r.get(0)).map_err(|e|e.to_string())?;Ok(SaveBackupRecord{id,emulator_id:emulator_id.map(str::to_string),source_path:source.into(),backup_path:dst.to_string_lossy().to_string(),created_at})}
pub fn restore_save_backup(c:&Connection,id:&str)->Result<(),String>{let (src,dst):(String,String)=c.query_row("SELECT backup_path,source_path FROM save_backups WHERE id=?1",[id],|r|Ok((r.get(0)?,r.get(1)?))).map_err(|e|e.to_string())?;let backup=Path::new(&src);let target=Path::new(&dst);if !backup.exists(){return Err("Backup não existe mais".into())}if target.exists(){return Err("Restauração recusada porque o destino já existe. Faça backup/remova explicitamente o destino antes de restaurar.".into())}copy_tree(backup,target)}
pub fn save_backups(c:&Connection)->Result<Vec<SaveBackupRecord>,String>{let mut s=c.prepare("SELECT id,emulator_id,source_path,backup_path,created_at FROM save_backups ORDER BY created_at DESC").map_err(|e|e.to_string())?;let result=s.query_map([],|r|Ok(SaveBackupRecord{id:r.get(0)?,emulator_id:r.get(1)?,source_path:r.get(2)?,backup_path:r.get(3)?,created_at:r.get(4)?})).map_err(|e|e.to_string())?.collect::<Result<Vec<_>,_>>().map_err(|e|e.to_string());result}

'''
    t=t[:idx]+add+t[idx:]

# Upgrade migration test expectation and add scale/sync tests.
t=t.replace('assert_eq!(v, 2);','assert_eq!(v, 3);')
close=t.rfind('\n}')
if close>=0 and 'handles_five_thousand_games' not in t:
    tests=r'''
    #[test]
    fn handles_five_thousand_games(){let mut c=Connection::open_in_memory().unwrap();c.execute_batch("CREATE TABLE games(id TEXT PRIMARY KEY,title TEXT NOT NULL,platform TEXT NOT NULL,source TEXT NOT NULL,executable TEXT,favorite INTEGER NOT NULL DEFAULT 0,status TEXT NOT NULL DEFAULT 'Quero jogar',total_seconds INTEGER NOT NULL DEFAULT 0,normalized_title TEXT NOT NULL DEFAULT '',created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP); CREATE INDEX idx_games_title ON games(title);").unwrap();let tx=c.transaction().unwrap();for i in 0..5000{tx.execute("INSERT INTO games(id,title,platform,source,normalized_title) VALUES(?1,?2,'PC','synthetic',?2)",params![format!("g{i}"),format!("Game {i:04}")]).unwrap();}tx.commit().unwrap();let count:i64=c.query_row("SELECT COUNT(*) FROM games WHERE title LIKE 'Game 4%'",[],|r|r.get(0)).unwrap();assert_eq!(count,1000);}
'''
    t=t[:close]+tests+t[close:]
p.write_text(t,encoding='utf-8')
