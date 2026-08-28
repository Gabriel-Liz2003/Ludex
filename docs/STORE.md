# Loja e comparação de preços

A Loja do Ludex 0.9.2 é um agregador local de catálogo e preços. Ela não vende jogos e não processa pagamentos.

## Fontes

### Steam

O catálogo inicial e o preço brasileiro da Steam são consultados diretamente nos endpoints públicos da própria loja. A Steam funciona sem chave adicional.

### IsThereAnyDeal

Quando o usuário configura uma API key do IsThereAnyDeal (ITAD), o Ludex consulta ofertas por Steam AppID usando país `BR`. O ITAD fornece ofertas individuais de lojas autorizadas, incluindo a Nuuvem quando aquele título/oferta está disponível em sua base.

A API key é fornecida pelo usuário e armazenada protegida no Windows; nunca é incluída no código-fonte.

### GG.deals

Quando o usuário configura uma API key do GG.deals, o Ludex consulta o menor preço de lojas oficiais (`currentRetail`) e o menor preço agregado de keyshops (`currentKeyshops`) para a região brasileira. A resposta pública utilizada não identifica necessariamente qual keyshop originou o menor preço, por isso o Ludex exibe `Melhor keyshop · GG.deals` e fornece o link para a página detalhada do jogo no GG.deals.

Isso permite cobrir comparadores que incluem marketplaces como Eneba e Instant Gaming sem fazer scraping desses sites.

## Por que não fazer scraping de Eneba/Instant Gaming?

O Ludex evita scraping como dependência crítica. Eneba possui API oficial voltada a merchants/vendedores e não uma API pública simples de comparação para consumidor; Instant Gaming não fornece uma API pública de preços de consumidor adequada ao Ludex. Além disso, GG.deals proíbe scraping de seu site e fornece uma API própria.

Se essas lojas passarem a oferecer APIs públicas sustentáveis no futuro, podem ser adicionadas como adapters individuais.

## Segurança

- Chaves de Steam Web API, ITAD e GG.deals são protegidas para o usuário atual do Windows usando DPAPI.
- O frontend não recebe as chaves salvas; recebe apenas flags `configured`.
- O comando que abre URLs externas utiliza uma allowlist de domínios das fontes suportadas.
- O Ludex não armazena dados de cartão, login de lojas ou credenciais de pagamento.

## Região e moeda

A região padrão é `BR`. A Steam é consultada com `cc=br`; ITAD recebe o país configurado pelo usuário; GG.deals recebe `region=br`. As ofertas preservam a moeda retornada por cada fonte e são formatadas no frontend.
