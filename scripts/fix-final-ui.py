from pathlib import Path

main = Path('src/main.ts')
text = main.read_text(encoding='utf-8')
bad = "document.querySelectorAll<HTMLElement>('[data-cover-id]').forEach(node=>observer.observe(node))}\""
good = "document.querySelectorAll<HTMLElement>('[data-cover-id]').forEach(node=>observer.observe(node))}"
if bad in text:
    text = text.replace(bad, good, 1)
main.write_text(text, encoding='utf-8')

css = Path('src/styles.css')
styles = css.read_text(encoding='utf-8')
rule = '.cover-art img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;z-index:0}.cover-art b{z-index:2}.load-more{grid-column:1/-1;justify-self:center;margin:12px 0 24px}'
if rule not in styles:
    styles += '\n' + rule + '\n'
css.write_text(styles, encoding='utf-8')
