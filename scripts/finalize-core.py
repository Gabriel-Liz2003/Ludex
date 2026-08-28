from pathlib import Path

# Fix rusqlite iterator lifetimes and SQLite-compatible additive defaults.
p=Path('src-tauri/src/product.rs'); t=p.read_text(encoding='utf-8')
t=t.replace('''    ensure_column(\n        c,\n        "collections",\n        "updated_at",\n        "TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP",\n    )?;''','''    ensure_column(c, "collections", "updated_at", "TEXT")?;\n    c.execute("UPDATE collections SET updated_at=CURRENT_TIMESTAMP WHERE updated_at IS NULL", []).map_err(|e|e.to_string())?;''')
t=t.replace('''    ensure_column(\n        c,\n        "roms",\n        "updated_at",\n        "TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP",\n    )?;''','''    ensure_column(c, "roms", "updated_at", "TEXT")?;\n    c.execute("UPDATE roms SET updated_at=CURRENT_TIMESTAMP WHERE updated_at IS NULL", []).map_err(|e|e.to_string())?;''')

def fix_lifetime(text, marker):
    start=text.find(marker)
    if start<0: return text
    candidates=[x for x in [text.find('\npub fn ',start+len(marker)),text.find('\nfn ',start+len(marker)),text.find('\n#[cfg',start+len(marker))] if x>=0]
    end=min(candidates) if candidates else len(text)
    block=text[start:end]
    if 'let result = s.query_map' in block: return text
    qi=block.find('s.query_map(')
    if qi<0:return text
    block=block[:qi]+'let result = '+block[qi:]
    close=block.rfind('\n}')
    if close<0:return text
    # Last expression is a Result; terminate it and return local.
    block=block[:close].rstrip()+';\n    result'+block[close:]
    return text[:start]+block+text[end:]
for m in ['pub fn collections(', 'pub fn emulators(', 'pub fn roms(', 'fn named_times(', 'fn buckets(']: t=fix_lifetime(t,m)
p.write_text(t,encoding='utf-8')

# Provider sync key must be provider-specific rather than mutating steam.last_sync for every provider.
p=Path('src-tauri/src/db.rs'); t=p.read_text(encoding='utf-8')
old='''    let now = Utc::now().to_rfc3339();\n    connection.execute(\n        "INSERT INTO settings(key, value) VALUES ('steam.last_sync', ?1) ON CONFLICT(key) DO UPDATE SET value=excluded.value",\n        [&now],\n    ).map_err(|e| e.to_string())?;'''
new='''    let now = Utc::now().to_rfc3339();\n    let sync_key = format!("{provider}.last_sync");\n    connection.execute(\n        "INSERT INTO settings(key, value) VALUES (?1, ?2) ON CONFLICT(key) DO UPDATE SET value=excluded.value",\n        params![sync_key, now],\n    ).map_err(|e| e.to_string())?;'''
if old in t:t=t.replace(old,new)
p.write_text(t,encoding='utf-8')

# External discovery is safe for providers with a real installation directory; exclude shell-only/ambiguous providers.
p=Path('src-tauri/src/sessions.rs'); t=p.read_text(encoding='utf-8')
t=t.replace('fn load_steam_installations(db_path: &Path) -> Result<Vec<Installation>, String> {','fn load_external_installations(db_path: &Path) -> Result<Vec<Installation>, String> {')
t=t.replace("FROM installations WHERE provider='steam' AND installed=1 AND install_dir IS NOT NULL", "FROM installations WHERE provider IN ('steam','epic','gog','ea','ubisoft') AND installed=1 AND install_dir IS NOT NULL")
t=t.replace('load_steam_installations(&db_path)', 'load_external_installations(&db_path)')
t=t.replace('"Jogo Steam iniciado externamente detectado"', '"Jogo iniciado externamente detectado"')
p.write_text(t,encoding='utf-8')
