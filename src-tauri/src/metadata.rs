use std::{
    fs,
    path::{Path, PathBuf},
};

use rusqlite::{params, Connection};

use crate::providers::steam::SteamProvider;

pub trait MetadataProvider: Send + Sync {
    fn id(&self) -> &'static str;
    fn refresh(&self, connection: &Connection) -> Result<usize, String>;
}

pub struct SteamLocalMetadataProvider;

#[derive(Default)]
struct SteamArtwork {
    cover: Option<String>,
    hero: Option<String>,
}

impl SteamLocalMetadataProvider {
    fn artwork_for(root: &Path, app_id: &str) -> SteamArtwork {
        let cache = root.join("appcache").join("librarycache");
        let mut files = Vec::new();
        collect_candidates(&cache, app_id, 3, &mut files);
        let mut artwork = SteamArtwork::default();
        for path in files {
            let Some(name) = path.file_name().and_then(|value| value.to_str()) else {
                continue;
            };
            let lower = name.to_ascii_lowercase();
            let value = path.to_string_lossy().to_string();
            if artwork.cover.is_none()
                && (lower.contains("library_600x900")
                    || lower.contains("portrait")
                    || lower.contains("capsule_600x900"))
            {
                artwork.cover = Some(value.clone());
            }
            if artwork.hero.is_none()
                && (lower.contains("library_hero")
                    || lower.contains("hero")
                    || lower.contains("header"))
            {
                artwork.hero = Some(value);
            }
            if artwork.cover.is_some() && artwork.hero.is_some() {
                break;
            }
        }
        artwork
    }
}

impl MetadataProvider for SteamLocalMetadataProvider {
    fn id(&self) -> &'static str {
        "steam-local-cache"
    }

    fn refresh(&self, connection: &Connection) -> Result<usize, String> {
        let Some(root) = SteamProvider::detect_root() else {
            return Ok(0);
        };
        let mut statement = connection
            .prepare(
                "SELECT e.game_id,e.external_id FROM external_ids e WHERE e.provider='steam' ORDER BY e.external_id",
            )
            .map_err(|e| e.to_string())?;
        let rows = statement
            .query_map([], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
            })
            .map_err(|e| e.to_string())?
            .collect::<Result<Vec<_>, _>>()
            .map_err(|e| e.to_string())?;
        drop(statement);

        let mut updated = 0usize;
        for (game_id, app_id) in rows {
            let artwork = Self::artwork_for(&root, &app_id);
            if artwork.cover.is_none() && artwork.hero.is_none() {
                continue;
            }
            let changed = connection
                .execute(
                    "INSERT INTO game_metadata(game_id,cover,hero,source,manual,updated_at)
                     VALUES(?1,?2,?3,?4,0,CURRENT_TIMESTAMP)
                     ON CONFLICT(game_id) DO UPDATE SET
                       cover=CASE WHEN game_metadata.manual=0 THEN COALESCE(excluded.cover,game_metadata.cover) ELSE game_metadata.cover END,
                       hero=CASE WHEN game_metadata.manual=0 THEN COALESCE(excluded.hero,game_metadata.hero) ELSE game_metadata.hero END,
                       source=CASE WHEN game_metadata.manual=0 THEN excluded.source ELSE game_metadata.source END,
                       updated_at=CASE WHEN game_metadata.manual=0 THEN CURRENT_TIMESTAMP ELSE game_metadata.updated_at END",
                    params![game_id, artwork.cover, artwork.hero, self.id()],
                )
                .map_err(|e| e.to_string())?;
            updated += changed;
        }
        Ok(updated)
    }
}

pub fn refresh_local_metadata(connection: &Connection) -> Result<usize, String> {
    let providers: [&dyn MetadataProvider; 1] = [&SteamLocalMetadataProvider];
    let mut updated = 0usize;
    for provider in providers {
        updated += provider.refresh(connection)?;
    }
    Ok(updated)
}

fn collect_candidates(directory: &Path, app_id: &str, depth: usize, out: &mut Vec<PathBuf>) {
    if depth == 0 || !directory.is_dir() {
        return;
    }
    let Ok(entries) = fs::read_dir(directory) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            let folder_matches = path
                .file_name()
                .and_then(|value| value.to_str())
                .is_some_and(|value| value == app_id || depth > 1);
            if folder_matches {
                collect_candidates(&path, app_id, depth - 1, out);
            }
            continue;
        }
        let Some(name) = path.file_name().and_then(|value| value.to_str()) else {
            continue;
        };
        let lower = name.to_ascii_lowercase();
        if lower.starts_with(&app_id.to_ascii_lowercase())
            || path
                .parent()
                .and_then(|parent| parent.file_name())
                .and_then(|value| value.to_str())
                .is_some_and(|value| value == app_id)
        {
            if matches!(
                path.extension()
                    .and_then(|value| value.to_str())
                    .map(str::to_ascii_lowercase)
                    .as_deref(),
                Some("jpg" | "jpeg" | "png" | "webp")
            ) {
                out.push(path);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::collect_candidates;
    use std::{fs, path::PathBuf};
    use uuid::Uuid;

    #[test]
    fn finds_only_matching_local_artwork() {
        let root = std::env::temp_dir().join(format!("ludex-metadata-{}", Uuid::new_v4()));
        let app = root.join("1091500");
        fs::create_dir_all(&app).unwrap();
        fs::write(app.join("library_600x900.jpg"), b"x").unwrap();
        fs::write(root.join("other.jpg"), b"x").unwrap();
        let mut files: Vec<PathBuf> = Vec::new();
        collect_candidates(&root, "1091500", 3, &mut files);
        assert_eq!(files.len(), 1);
        assert!(files[0].ends_with("library_600x900.jpg"));
        let _ = fs::remove_dir_all(root);
    }
}
