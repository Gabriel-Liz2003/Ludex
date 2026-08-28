use crate::secrets;
use reqwest::blocking::Client;
use rusqlite::{Connection, OptionalExtension};
use serde::Serialize;
use serde_json::Value;
use std::collections::HashSet;
use std::time::Duration;

#[derive(Debug, Clone, Serialize)]
pub struct StoreSettingsStatus {
    pub ggdeals_configured: bool,
    pub itad_configured: bool,
    pub country: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct StoreCatalogItem {
    pub app_id: String,
    pub title: String,
    pub cover: Option<String>,
    pub price: Option<f64>,
    pub regular: Option<f64>,
    pub currency: Option<String>,
    pub discount_percent: i64,
}

#[derive(Debug, Clone, Serialize)]
pub struct StoreOffer {
    pub shop: String,
    pub kind: String,
    pub price: f64,
    pub regular: Option<f64>,
    pub currency: String,
    pub cut: i64,
    pub url: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct StoreComparison {
    pub app_id: String,
    pub title: String,
    pub cover: Option<String>,
    pub offers: Vec<StoreOffer>,
    pub gg_url: String,
    pub note: String,
}

fn client() -> Result<Client, String> {
    Client::builder()
        .timeout(Duration::from_secs(20))
        .user_agent("Ludex/0.9.2 (+https://github.com/Gabriel-Liz2003/Ludex)")
        .build()
        .map_err(|e| e.to_string())
}

pub fn settings(connection: &Connection) -> Result<StoreSettingsStatus, String> {
    Ok(StoreSettingsStatus {
        ggdeals_configured: secrets::configured(connection, "ggdeals.api_key")?,
        itad_configured: secrets::configured(connection, "itad.api_key")?,
        country: crate::db::get_setting(connection, "store.country")?
            .unwrap_or_else(|| "BR".into()),
    })
}

pub fn save_keys(
    connection: &Connection,
    ggdeals: Option<&str>,
    itad: Option<&str>,
    country: &str,
) -> Result<(), String> {
    if let Some(value) = ggdeals {
        secrets::set(connection, "ggdeals.api_key", value)?;
    }
    if let Some(value) = itad {
        secrets::set(connection, "itad.api_key", value)?;
    }
    let country = country.trim().to_ascii_uppercase();
    if country.len() != 2 || !country.chars().all(|c| c.is_ascii_alphabetic()) {
        return Err("País inválido: use código ISO de 2 letras, como BR".into());
    }
    connection
        .execute(
            "INSERT INTO settings(key,value) VALUES('store.country',?1) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            [country],
        )
        .map_err(|e| e.to_string())?;
    Ok(())
}

fn amount_cents(value: Option<i64>) -> Option<f64> {
    value.map(|v| v as f64 / 100.0)
}

pub fn catalog(connection: &Connection) -> Result<Vec<StoreCatalogItem>, String> {
    let request = client()?
        .get("https://store.steampowered.com/api/featuredcategories/")
        .query(&[("cc", "br"), ("l", "brazilian")])
        .send();
    if let Ok(response) = request {
        if response.status().is_success() {
            if let Ok(value) = response.json::<Value>() {
                let mut items = Vec::new();
                let mut seen = HashSet::new();
                for category in ["specials", "top_sellers", "new_releases", "coming_soon"] {
                    let Some(array) = value
                        .get(category)
                        .and_then(|v| v.get("items"))
                        .and_then(Value::as_array)
                    else {
                        continue;
                    };
                    for item in array {
                        let Some(id) = item.get("id").and_then(Value::as_u64) else {
                            continue;
                        };
                        if !seen.insert(id) {
                            continue;
                        }
                        let title = item
                            .get("name")
                            .and_then(Value::as_str)
                            .unwrap_or("")
                            .trim();
                        if title.is_empty() {
                            continue;
                        }
                        let final_price = item.get("final_price").and_then(Value::as_i64);
                        let original_price = item.get("original_price").and_then(Value::as_i64);
                        let currency = item
                            .get("currency")
                            .and_then(Value::as_str)
                            .map(str::to_string);
                        let cover = item
                            .get("large_capsule_image")
                            .or_else(|| item.get("header_image"))
                            .and_then(Value::as_str)
                            .map(str::to_string)
                            .or_else(|| Some(format!("steam-artwork:{id}")));
                        items.push(StoreCatalogItem {
                            app_id: id.to_string(),
                            title: title.to_string(),
                            cover,
                            price: amount_cents(final_price),
                            regular: amount_cents(original_price),
                            currency,
                            discount_percent: item
                                .get("discount_percent")
                                .and_then(Value::as_i64)
                                .unwrap_or(0),
                        });
                        if items.len() >= 80 {
                            break;
                        }
                    }
                    if items.len() >= 80 {
                        break;
                    }
                }
                if !items.is_empty() {
                    return Ok(items);
                }
            }
        }
    }
    let mut statement = connection
        .prepare(
            "SELECT e.external_id,g.title,m.cover FROM external_ids e JOIN games g ON g.id=e.game_id LEFT JOIN game_metadata m ON m.game_id=g.id WHERE e.provider='steam' ORDER BY g.favorite DESC,g.title COLLATE NOCASE LIMIT 80",
        )
        .map_err(|e| e.to_string())?;
    let result = statement
        .query_map([], |row| {
            Ok(StoreCatalogItem {
                app_id: row.get(0)?,
                title: row.get(1)?,
                cover: row.get(2)?,
                price: None,
                regular: None,
                currency: None,
                discount_percent: 0,
            })
        })
        .map_err(|e| e.to_string())?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string());
    drop(statement);
    result
}

fn steam_offer(
    http: &Client,
    app_id: &str,
) -> Result<(String, Option<String>, Option<StoreOffer>), String> {
    let response = http
        .get("https://store.steampowered.com/api/appdetails")
        .query(&[("appids", app_id), ("cc", "br"), ("l", "brazilian")])
        .send()
        .map_err(|e| e.to_string())?;
    if !response.status().is_success() {
        return Ok((
            format!("Steam App {app_id}"),
            Some(format!("steam-artwork:{app_id}")),
            None,
        ));
    }
    let value: Value = response.json().map_err(|e| e.to_string())?;
    let data = value.get(app_id).and_then(|v| v.get("data"));
    let title = data
        .and_then(|v| v.get("name"))
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_string();
    let cover = data
        .and_then(|v| v.get("header_image"))
        .and_then(Value::as_str)
        .map(str::to_string);
    let free = data
        .and_then(|v| v.get("is_free"))
        .and_then(Value::as_bool)
        .unwrap_or(false);
    if free {
        return Ok((
            title,
            cover,
            Some(StoreOffer {
                shop: "Steam".into(),
                kind: "official".into(),
                price: 0.0,
                regular: Some(0.0),
                currency: "BRL".into(),
                cut: 0,
                url: format!("https://store.steampowered.com/app/{app_id}/"),
            }),
        ));
    }
    let price = data.and_then(|v| v.get("price_overview"));
    let final_price = price.and_then(|v| v.get("final")).and_then(Value::as_i64);
    let regular = price.and_then(|v| v.get("initial")).and_then(Value::as_i64);
    let Some(final_price) = final_price else {
        return Ok((title, cover, None));
    };
    Ok((
        title,
        cover,
        Some(StoreOffer {
            shop: "Steam".into(),
            kind: "official".into(),
            price: final_price as f64 / 100.0,
            regular: regular.map(|v| v as f64 / 100.0),
            currency: price
                .and_then(|v| v.get("currency"))
                .and_then(Value::as_str)
                .unwrap_or("BRL")
                .to_string(),
            cut: price
                .and_then(|v| v.get("discount_percent"))
                .and_then(Value::as_i64)
                .unwrap_or(0),
            url: format!("https://store.steampowered.com/app/{app_id}/"),
        }),
    ))
}

fn itad_offers(
    connection: &Connection,
    http: &Client,
    app_id: &str,
    country: &str,
) -> Result<Vec<StoreOffer>, String> {
    let Some(key) = secrets::get(connection, "itad.api_key")? else {
        return Ok(Vec::new());
    };
    let lookup = http
        .get("https://api.isthereanydeal.com/games/lookup/v1")
        .header("ITAD-API-Key", &key)
        .query(&[("appid", app_id)])
        .send()
        .map_err(|e| format!("IsThereAnyDeal lookup: {e}"))?;
    if !lookup.status().is_success() {
        return Ok(Vec::new());
    }
    let game: Value = lookup.json().map_err(|e| e.to_string())?;
    let Some(id) = game.get("id").and_then(Value::as_str) else {
        return Ok(Vec::new());
    };
    let prices = http
        .post("https://api.isthereanydeal.com/games/prices/v3")
        .header("ITAD-API-Key", &key)
        .query(&[
            ("country", country),
            ("deals", "false"),
            ("vouchers", "true"),
        ])
        .json(&vec![id])
        .send()
        .map_err(|e| format!("IsThereAnyDeal prices: {e}"))?;
    if !prices.status().is_success() {
        return Ok(Vec::new());
    }
    let value: Value = prices.json().map_err(|e| e.to_string())?;
    let mut out = Vec::new();
    let deals = value
        .as_array()
        .and_then(|a| a.first())
        .and_then(|v| v.get("deals"))
        .and_then(Value::as_array);
    for deal in deals.into_iter().flatten() {
        let shop = deal
            .get("shop")
            .and_then(|v| v.get("name"))
            .and_then(Value::as_str)
            .unwrap_or("Loja");
        let Some(price) = deal
            .get("price")
            .and_then(|v| v.get("amount"))
            .and_then(Value::as_f64)
        else {
            continue;
        };
        let currency = deal
            .get("price")
            .and_then(|v| v.get("currency"))
            .and_then(Value::as_str)
            .unwrap_or("BRL");
        let regular = deal
            .get("regular")
            .and_then(|v| v.get("amount"))
            .and_then(Value::as_f64);
        let url = deal.get("url").and_then(Value::as_str).unwrap_or("");
        if url.is_empty() {
            continue;
        }
        out.push(StoreOffer {
            shop: shop.to_string(),
            kind: "official".into(),
            price,
            regular,
            currency: currency.to_string(),
            cut: deal.get("cut").and_then(Value::as_i64).unwrap_or(0),
            url: url.to_string(),
        });
    }
    Ok(out)
}

fn json_number(value: Option<&Value>) -> Option<f64> {
    value.and_then(|v| {
        v.as_f64()
            .or_else(|| v.as_str().and_then(|s| s.parse::<f64>().ok()))
    })
}

fn ggdeals_offers(
    connection: &Connection,
    http: &Client,
    app_id: &str,
) -> Result<(Vec<StoreOffer>, Option<String>), String> {
    let Some(key) = secrets::get(connection, "ggdeals.api_key")? else {
        return Ok((Vec::new(), None));
    };
    let response = http
        .get("https://api.gg.deals/v1/prices/by-steam-app-id/")
        .query(&[("ids", app_id), ("key", key.as_str()), ("region", "br")])
        .send()
        .map_err(|e| format!("GG.deals: {e}"))?;
    if !response.status().is_success() {
        return Ok((Vec::new(), None));
    }
    let value: Value = response.json().map_err(|e| e.to_string())?;
    if value.get("success").and_then(Value::as_bool) != Some(true) {
        return Ok((Vec::new(), None));
    }
    let Some(game) = value.get("data").and_then(|v| v.get(app_id)) else {
        return Ok((Vec::new(), None));
    };
    if game.is_null() {
        return Ok((Vec::new(), None));
    }
    let url = game.get("url").and_then(Value::as_str).map(str::to_string);
    let prices = game.get("prices").unwrap_or(&Value::Null);
    let currency = prices
        .get("currency")
        .and_then(Value::as_str)
        .unwrap_or("BRL");
    let mut offers = Vec::new();
    if let Some(price) = json_number(prices.get("currentRetail")) {
        offers.push(StoreOffer {
            shop: "Melhor loja oficial · GG.deals".into(),
            kind: "official".into(),
            price,
            regular: None,
            currency: currency.to_string(),
            cut: 0,
            url: url
                .clone()
                .unwrap_or_else(|| format!("https://gg.deals/steam/app/{app_id}/")),
        });
    }
    if let Some(price) = json_number(prices.get("currentKeyshops")) {
        offers.push(StoreOffer {
            shop: "Melhor keyshop · GG.deals".into(),
            kind: "keyshop".into(),
            price,
            regular: None,
            currency: currency.to_string(),
            cut: 0,
            url: url
                .clone()
                .unwrap_or_else(|| format!("https://gg.deals/steam/app/{app_id}/")),
        });
    }
    Ok((offers, url))
}

pub fn compare(connection: &Connection, app_id: &str) -> Result<StoreComparison, String> {
    if app_id.is_empty() || !app_id.chars().all(|c| c.is_ascii_digit()) {
        return Err("Steam AppID inválido".into());
    }
    let http = client()?;
    let (steam_title, steam_cover, steam) = steam_offer(&http, app_id)?;
    let title = connection
        .query_row(
            "SELECT g.title FROM external_ids e JOIN games g ON g.id=e.game_id WHERE e.provider='steam' AND e.external_id=?1",
            [app_id],
            |row| row.get::<_, String>(0),
        )
        .optional()
        .map_err(|e| e.to_string())?
        .filter(|v| !v.trim().is_empty())
        .unwrap_or_else(|| if steam_title.is_empty() { format!("Steam App {app_id}") } else { steam_title });
    let country =
        crate::db::get_setting(connection, "store.country")?.unwrap_or_else(|| "BR".into());
    let mut offers = Vec::new();
    if let Some(offer) = steam {
        offers.push(offer);
    }
    offers.extend(itad_offers(connection, &http, app_id, &country)?);
    let (gg, gg_url) = ggdeals_offers(connection, &http, app_id)?;
    offers.extend(gg);
    let mut seen = HashSet::new();
    offers.retain(|o| {
        seen.insert(format!(
            "{}:{}:{:.2}:{}",
            o.shop.to_ascii_lowercase(),
            o.currency,
            o.price,
            o.url
        ))
    });
    offers.sort_by(|a, b| {
        a.price
            .partial_cmp(&b.price)
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    Ok(StoreComparison {
        app_id: app_id.to_string(), title, cover: steam_cover.or_else(|| Some(format!("steam-artwork:{app_id}"))), offers,
        gg_url: gg_url.unwrap_or_else(|| format!("https://gg.deals/steam/app/{app_id}/")),
        note: "Lojas oficiais individuais vêm da API do IsThereAnyDeal. Keyshops como Eneba e Instant Gaming são comparadas pelo feed oficial do GG.deals quando disponível; a API pública atual do GG.deals expõe o menor preço de keyshops, mas nem sempre o nome individual da loja. O Ludex não faz scraping de marketplaces.".into(),
    })
}

#[cfg(test)]
mod tests {
    #[test]
    fn money_cents_are_converted() {
        assert_eq!(super::amount_cents(Some(1299)), Some(12.99));
    }
}
