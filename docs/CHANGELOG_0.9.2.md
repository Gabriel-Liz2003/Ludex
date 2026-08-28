# Ludex 0.9.2

## Destaques

- Corrige a resolução de capas Steam para layouts modernos de `librarycache`, incluindo `library_capsule`, com fallback para metadata da Steam Store.
- Adiciona importação opcional da biblioteca completa da conta Steam via Web API, incluindo jogos não instalados.
- Mantém estado instalado e launch vindos dos manifests locais; jogos da conta não instalados aparecem na biblioteca sem fingir instalação.
- Importa playtime histórico retornado pela Steam para `imported_playtime`, separado das sessões do Ludex.
- Adiciona a nova página **Loja**, com catálogo e preços Steam para o Brasil.
- Adiciona integração opcional com IsThereAnyDeal para preços individuais de lojas autorizadas (incluindo Nuuvem quando disponível na base).
- Adiciona integração opcional com GG.deals para melhor preço agregado de lojas oficiais e keyshops e acesso à página detalhada do comparador.
- Credenciais de Steam Web API, ITAD e GG.deals ficam protegidas para o usuário atual do Windows usando DPAPI.

## Compatibilidade

A atualização preserva o banco existente e não altera IDs já importados. Instalações Steam continuam usando a identidade estável `steam:<appid>`.

## Limitações conhecidas

- A biblioteca completa da Steam depende da Steam Web API e da visibilidade/permissões aplicáveis à conta.
- Eneba e Instant Gaming não são raspadas diretamente. A API pública usada do GG.deals expõe o menor preço agregado de keyshops e um link detalhado; nomes individuais dependem do que a API expõe.
- A confirmação visual da capa de um jogo específico depende do cache/metadata disponíveis na máquina do usuário ou da resposta da Steam Store.
