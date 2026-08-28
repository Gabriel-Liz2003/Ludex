from pathlib import Path
import base64

ICON = "AAABAAIAEBAAAAAAIAADAgAAJgAAACAgAAAAACAA5QAAACkCAACJUE5HDQoaCgAAAA1JSERSAAAAEAAAABAIBgAAAB/z/2EAAAHKSURBVHicfZO/bhNBEMZ/M7dnnw1EjuUOR6GyhBv8DEH8kUgHD0MKmjwSouAFEA3CBSVSxB/TIaEkts8+7+5Q3PlMbIdPmmZ3vm9nv5mRbn9orCGKRQ+2OdqGaAIidY77lxxXOS47AE32iogofnmNGKAOLFYCovj5HzqDx/SfvEYIO3yzSJK2mU4+8/3dG7ACSVLc+uXO4ITjF+eIKotlQeoUVbkpEgOdwQmcCj/en4NF1ILHZQccPT1DVFjkcx4dNbmXGd57LG4CYDX9zeHwGb3RK3x+iYKBJFgMiAWWXhgdt+i0E0IEQaCOyujVEpEEAK2KW9uECsyLSF4YPtzSDZGa47bvosGdptLvpuSFkRdxv0gFvWES0HDCl58LHvQaDO83/0veqcAMsobw6SLn49c5LhGyVG7jbglYrEVaDaEtgln5pV1Yne/KLiiatrDqMBobX3e4Zdc0bYEZKuoIy2umkzEuO8CCx2LYH2GFuAaxmDGdjNE0w5X9tXI8T4XDh8+JfoHI1hSaIZoQihnf3p5xdfEB1+og3f7QEMVCgSQNeqOXSLUo9fBQ7oKmLWa/xjXZYqgEoF5Rn19WBuxzP6IuI2nexWIA4C/GlOMbR1HDmgAAAABJRU5ErkJggolQTkcNChoKAAAADUlIRFIAAAAgAAAAIAgGAAAAc3p69AAAAKxJREFUeJxjFJLR+s8wgIBpIC0fdQADAwMDCyEFWmmbKLbk2iw/nHJ4Q4AalhMyB6cDqGU5IfOwOoDaluMzF8MBtLIcl/kk5YIdZYpUdQzJDqAFGHXAqAMG3AEE6wIUxcyMDNMSpOH8y49/MEzf+5Z+DnBpv0eRZdjAgEfBqANGHTDqAAwH4GtAUgOgm481BGjlCGzm4owCajsCl3l40wC1HIHPHMbRzulAOwAAicEm8x6ApZwAAAAASUVORK5CYII="

icon_path = Path("src-tauri/icons/icon.ico")
icon_path.parent.mkdir(parents=True, exist_ok=True)
icon_path.write_bytes(base64.b64decode(ICON))

db_path = Path("src-tauri/src/db.rs")
text = db_path.read_text(encoding="utf-8")

old = '("updated_at", "TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP"),'
new = '("updated_at", "TEXT"),'
if old in text:
    text = text.replace(old, new, 1)
elif new not in text:
    raise SystemExit("expected updated_at migration definition not found")

marker = '         CREATE INDEX IF NOT EXISTS idx_external_ids_game ON external_ids(game_id);"\n    )'
replacement = '         CREATE INDEX IF NOT EXISTS idx_external_ids_game ON external_ids(game_id);\n         UPDATE installations SET updated_at=CURRENT_TIMESTAMP WHERE updated_at IS NULL;"\n    )'
if marker in text:
    text = text.replace(marker, replacement, 1)
elif "UPDATE installations SET updated_at=CURRENT_TIMESTAMP WHERE updated_at IS NULL;" not in text:
    raise SystemExit("migration batch marker not found")

if "mod import_tests" not in text:
    text += r'''

#[cfg(test)]
mod import_tests {
    use super::{add_manual_game, import_installations, list_games, open};
    use crate::models::ScannedInstallation;
    use std::fs;
    use uuid::Uuid;

    fn temp_db() -> std::path::PathBuf {
        std::env::temp_dir().join(format!("ludex-test-{}.db", Uuid::new_v4()))
    }

    fn steam_game(external_id: &str, title: &str) -> ScannedInstallation {
        ScannedInstallation {
            provider: "steam".into(),
            external_id: external_id.into(),
            title: title.into(),
            platform: "PC".into(),
            install_dir: Some("C:\\Games\\Cyberpunk 2077".into()),
            executable: None,
            installed: true,
            size_bytes: Some(100),
            last_updated: Some(1),
        }
    }

    #[test]
    fn steam_reimport_is_idempotent() {
        let path = temp_db();
        let connection = open(&path).unwrap();
        let item = steam_game("1091500", "Cyberpunk 2077™");
        import_installations(
            &connection,
            "steam",
            std::slice::from_ref(&item),
            "C:\\Steam",
            1,
        )
        .unwrap();
        import_installations(
            &connection,
            "steam",
            std::slice::from_ref(&item),
            "C:\\Steam",
            1,
        )
        .unwrap();

        assert_eq!(list_games(&connection).unwrap().len(), 1);
        let count: i64 = connection
            .query_row(
                "SELECT COUNT(*) FROM installations WHERE provider='steam'",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(count, 1);
        drop(connection);
        let _ = fs::remove_file(path);
    }

    #[test]
    fn unique_normalized_title_deduplicates_manual_identity() {
        let path = temp_db();
        let connection = open(&path).unwrap();
        add_manual_game(
            &connection,
            "manual-cp",
            "Cyberpunk 2077",
            "PC",
            None,
            None,
            None,
        )
        .unwrap();

        let result = import_installations(
            &connection,
            "steam",
            &[steam_game("1091500", "Cyberpunk 2077™")],
            "C:\\Steam",
            1,
        )
        .unwrap();

        assert_eq!(result.games_created, 0);
        assert_eq!(list_games(&connection).unwrap().len(), 1);
        drop(connection);
        let _ = fs::remove_file(path);
    }
}
'''

db_path.write_text(text, encoding="utf-8")
