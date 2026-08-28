from pathlib import Path

p=Path('src-tauri/src/product.rs')
t=p.read_text(encoding='utf-8')
# Force query_map temporaries to be dropped before statements.
for marker in ['pub fn collections(', 'pub fn emulators(', 'pub fn roms(', 'fn named_times(', 'fn buckets(']:
    start=t.find(marker)
    if start<0: continue
    end=t.find('\n}', start)
    if end<0: continue
    block=t[start:end+2]
    if '.collect::<Result<Vec<_>, _>>()\n        .map_err(|e| e.to_string())' in block and 'let result = s.query_map' not in block:
        block=block.replace('s.query_map([], |r| {','let result = s.query_map([], |r| {',1)
        block=block.replace('.collect::<Result<Vec<_>, _>>()\n        .map_err(|e| e.to_string())\n}', '.collect::<Result<Vec<_>, _>>()\n        .map_err(|e| e.to_string());\n    result\n}',1)
        t=t[:start]+block+t[end+2:]
p.write_text(t,encoding='utf-8')

p=Path('src-tauri/src/providers/gog.rs')
t=p.read_text(encoding='utf-8')
old='''            let title = value\n                .get("name")\n                .and_then(Value::as_str)\n                .unwrap_or_else(|| child.file_name().to_str().unwrap_or("GOG Game"))\n                .to_string();'''
new='''            let fallback_title = child.file_name().to_string_lossy().to_string();\n            let title = value\n                .get("name")\n                .and_then(Value::as_str)\n                .map(str::to_string)\n                .unwrap_or(fallback_title);'''
if old not in t: raise SystemExit('gog title pattern not found')
t=t.replace(old,new)
p.write_text(t,encoding='utf-8')

p=Path('src-tauri/src/providers/microsoft.rs')
t=p.read_text(encoding='utf-8').replace('use std::{path::PathBuf, process::Command};','use std::process::Command;')
p.write_text(t,encoding='utf-8')

p=Path('src-tauri/src/lib.rs')
t=p.read_text(encoding='utf-8').replace('use tracing::{error, info};','use tracing::error;')
old='''    value\n        .chars()\n        .map(|c| match c {\n            ':' => "%3A".into(),\n            ' ' => "%20".into(),\n            c if c.is_ascii_alphanumeric() || "-_.~".contains(c) => c.to_string(),\n            c => format!("%{:02X}", c as u32),\n        })\n        .collect()'''
new='''    value\n        .chars()\n        .map(|c| match c {\n            ':' => "%3A".to_string(),\n            ' ' => "%20".to_string(),\n            c if c.is_ascii_alphanumeric() || "-_.~".contains(c) => c.to_string(),\n            c => format!("%{:02X}", c as u32),\n        })\n        .collect::<Vec<_>>()\n        .join("")'''
if old in t:t=t.replace(old,new)
p.write_text(t,encoding='utf-8')
