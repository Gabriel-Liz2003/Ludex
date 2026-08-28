from pathlib import Path

p=Path('src-tauri/src/store.rs')
t=p.read_text(encoding='utf-8')
old='''    statement
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
        .map_err(|e| e.to_string())
}'''
new='''    let result = statement
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
}'''
if old not in t: raise SystemExit('catalog lifetime pattern not found')
t=t.replace(old,new,1)
# GG.deals currently documents price fields that may arrive as JSON numbers or numeric strings.
marker='''fn ggdeals_offers(connection: &Connection, http: &Client, app_id: &str) -> Result<(Vec<StoreOffer>, Option<String>), String> {'''
helper='''fn json_number(value: Option<&Value>) -> Option<f64> {
    value.and_then(|v| v.as_f64().or_else(|| v.as_str().and_then(|s| s.parse::<f64>().ok())))
}

'''
if helper not in t:
    if marker not in t: raise SystemExit('ggdeals marker not found')
    t=t.replace(marker,helper+marker,1)
t=t.replace('prices.get("currentRetail").and_then(Value::as_f64)','json_number(prices.get("currentRetail"))')
t=t.replace('prices.get("currentKeyshops").and_then(Value::as_f64)','json_number(prices.get("currentKeyshops"))')
p.write_text(t,encoding='utf-8')

# Ensure generated lib accepts ITAD redirect URLs used by the API.
p=Path('scripts/apply-0.9.2.py')
t=p.read_text(encoding='utf-8')
old='''"https://isthereanydeal.com/", "https://docs.isthereanydeal.com/"'''
new='''"https://isthereanydeal.com/", "https://next.isthereanydeal.com/", "https://docs.isthereanydeal.com/"'''
if old not in t: raise SystemExit('allowlist pattern not found')
p.write_text(t.replace(old,new,1),encoding='utf-8')
