from pathlib import Path

product = Path('src-tauri/src/product.rs')
text = product.read_text(encoding='utf-8')
# Force Statement destruction before returning collected results. The collected Result<Vec<_>, String>
# does not borrow the statement, but this makes the lifetime boundary explicit for rustc.
text = text.replace('\n    result\n}', '\n    drop(s);\n    result\n}')
product.write_text(text, encoding='utf-8')

gog = Path('src-tauri/src/providers/gog.rs')
text = gog.read_text(encoding='utf-8')
old = '''            let title = json\n                .get("name")\n                .and_then(|v| v.as_str())\n                .unwrap_or_else(|| child.file_name().to_str().unwrap_or("GOG Game"))\n                .to_string();'''
new = '''            let fallback_title = child.file_name().to_string_lossy().into_owned();\n            let title = json\n                .get("name")\n                .and_then(|v| v.as_str())\n                .map(str::to_owned)\n                .unwrap_or(fallback_title);'''
if old not in text:
    raise SystemExit('GOG title fallback pattern not found')
text = text.replace(old, new, 1)
gog.write_text(text, encoding='utf-8')

ms = Path('src-tauri/src/providers/microsoft.rs')
text = ms.read_text(encoding='utf-8').replace('use std::{path::PathBuf, process::Command};', 'use std::process::Command;')
ms.write_text(text, encoding='utf-8')

lib = Path('src-tauri/src/lib.rs')
text = lib.read_text(encoding='utf-8').replace('use tracing::{error, info};', 'use tracing::error;')
lib.write_text(text, encoding='utf-8')
