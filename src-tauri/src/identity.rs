const EDITION_SUFFIXES: &[&str] = &[
    "digital deluxe edition",
    "game of the year edition",
    "definitive edition",
    "ultimate edition",
    "complete edition",
    "deluxe edition",
    "standard edition",
    "goty edition",
    "remastered",
    "remaster",
];

fn cleanup_text(title: &str) -> String {
    title
        .replace('™', "")
        .replace('®', "")
        .replace('©', "")
        .to_lowercase()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

pub fn normalize_title(title: &str) -> String {
    let mut value = cleanup_text(title);

    for suffix in EDITION_SUFFIXES {
        if value == *suffix {
            break;
        }
        if let Some(prefix) = value.strip_suffix(suffix) {
            let trimmed = prefix.trim_end_matches(|c: char| {
                c.is_whitespace() || matches!(c, '-' | ':' | '–' | '—' | '(' | '[')
            });
            if !trimmed.is_empty() {
                value = trimmed.to_string();
                break;
            }
        }
    }

    let normalized = value
        .chars()
        .map(|c| if c.is_alphanumeric() { c } else { ' ' })
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");

    normalized
}

#[cfg(test)]
mod tests {
    use super::normalize_title;

    #[test]
    fn removes_trademarks_and_punctuation() {
        assert_eq!(normalize_title("Cyberpunk 2077™"), "cyberpunk 2077");
        assert_eq!(normalize_title("NieR:Automata®"), "nier automata");
    }

    #[test]
    fn strips_conservative_edition_suffixes() {
        assert_eq!(normalize_title("Control Ultimate Edition"), "control");
        assert_eq!(normalize_title("Hades - Standard Edition"), "hades");
    }

    #[test]
    fn does_not_remove_meaningful_middle_words() {
        assert_eq!(normalize_title("Deluxe Ski Jump 4"), "deluxe ski jump 4");
    }
}
