from pathlib import Path

product = Path('src-tauri/src/product.rs')
text = product.read_text(encoding='utf-8')
# Materialize query results and make the Statement lifetime boundary explicit.
# Keep the transformation idempotent so the helper can be retried safely.
text = text.replace('\n    result\n}', '\n    drop(s);\n    result\n}')
product.write_text(text, encoding='utf-8')

# GOG may already contain the owned fallback from a prior repair; only rewrite the legacy form.
gog = Path('src-tauri/src/providers/gog.rs')
text = gog.read_text(encoding='utf-8')
old = '''            let title = value
                .get("name")
                .and_then(Value::as_str)
                .unwrap_or_else(|| child.file_name().to_str().unwrap_or("GOG Game"))
                .to_string();'''
new = '''            let fallback_title = child.file_name().to_string_lossy().into_owned();
            let title = value
                .get("name")
                .and_then(Value::as_str)
                .map(str::to_owned)
                .unwrap_or(fallback_title);'''
if old in text:
    text = text.replace(old, new, 1)
gog.write_text(text, encoding='utf-8')

ms = Path('src-tauri/src/providers/microsoft.rs')
text = ms.read_text(encoding='utf-8').replace(
    'use std::{path::PathBuf, process::Command};',
    'use std::process::Command;'
)
ms.write_text(text, encoding='utf-8')

lib = Path('src-tauri/src/lib.rs')
text = lib.read_text(encoding='utf-8').replace(
    'use tracing::{error, info};',
    'use tracing::error;'
)
lib.write_text(text, encoding='utf-8')
