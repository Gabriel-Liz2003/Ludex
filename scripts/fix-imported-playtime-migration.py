from pathlib import Path

path = Path('src-tauri/src/db.rs')
text = path.read_text(encoding='utf-8')
needle = '''         CREATE INDEX IF NOT EXISTS idx_sessions_started ON play_sessions(started_at DESC);\n         CREATE TABLE IF NOT EXISTS collections (id TEXT PRIMARY KEY, name TEXT NOT NULL UNIQUE);'''
replacement = '''         CREATE INDEX IF NOT EXISTS idx_sessions_started ON play_sessions(started_at DESC);\n         CREATE TABLE IF NOT EXISTS imported_playtime (\n           game_id TEXT NOT NULL REFERENCES games(id) ON DELETE CASCADE,\n           provider TEXT NOT NULL,\n           seconds INTEGER NOT NULL DEFAULT 0,\n           updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,\n           PRIMARY KEY(game_id, provider)\n         );\n         CREATE INDEX IF NOT EXISTS idx_imported_playtime_game ON imported_playtime(game_id);\n         CREATE TABLE IF NOT EXISTS collections (id TEXT PRIMARY KEY, name TEXT NOT NULL UNIQUE);'''
if text.count(needle) != 1:
    raise SystemExit(f'expected one migration insertion point, got {text.count(needle)}')
path.write_text(text.replace(needle, replacement, 1), encoding='utf-8')
