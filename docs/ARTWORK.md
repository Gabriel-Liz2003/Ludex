# Artwork da Steam

O Ludex 0.9.2 não depende mais de um único nome fixo de imagem para todos os jogos Steam.

## Resolução

Para referências `steam-artwork:<appid>` e `steam-artwork-hero:<appid>`, o backend tenta:

1. cache local da Steam em `appcache/librarycache`, procurando assets associados ao AppID;
2. padrões modernos e antigos, incluindo `library_capsule`, `library_600x900`, `capsule_600x900`, `portrait`, `library_hero`, `hero` e `background`;
3. quando não há asset local utilizável, consulta `store.steampowered.com/api/appdetails` e usa o `header_image` fornecido pela própria Steam.

O frontend não recebe permissão ampla para ler o filesystem. Imagens locais passam pelo comando controlado do backend, que só aceita extensões de imagem conhecidas e impõe limite de tamanho.

Isso corrige jogos cujo cache atual da Steam usa nomes diferentes dos padrões antigos e reduz a chance de títulos novos aparecerem apenas com iniciais.
