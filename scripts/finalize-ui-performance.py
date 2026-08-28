from pathlib import Path

p = Path("src/main.ts")
t = p.read_text(encoding="utf-8")

broken = "catch(()=>{})}}},{rootMargin:'300px'}"
fixed = "catch(()=>{})}},{rootMargin:'300px'}"

if broken in t:
    t = t.replace(broken, fixed, 1)

# Repair the earlier stray quote variant too, if present.
t = t.replace("observer.observe(node))}\"\n", "observer.observe(node))}\n", 1)

if broken in t:
    raise SystemExit("hydrateArtwork still contains an extra closing brace")

p.write_text(t, encoding="utf-8")
