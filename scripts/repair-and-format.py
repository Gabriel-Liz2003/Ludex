from pathlib import Path

frontend = Path('src/main.ts')
text = frontend.read_text(encoding='utf-8')
text = text.replace("let mode:<'grid'|'list'>='grid';", "let mode:'grid'|'list'='grid';")
frontend.write_text(text, encoding='utf-8')
