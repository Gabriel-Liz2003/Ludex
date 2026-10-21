# Capas dos jogos no Android (v0.9.44)

O Ludex preserva as horas e os jogos mesmo quando a capa não pode ser encontrada.

## Fontes de imagens

1. Capas já disponibilizadas por Steam, Epic, PlayStation, Xbox ou Nintendo (quando o registro contém ID/URL).
2. Para jogos de bibliotecas públicas sem imagem, consulta o SteamGridDB **somente se a API Key estiver configurada**, exigindo correspondência exata do título normalizado.
3. Para League of Legends e VALORANT, usa imagens públicas do press kit da Riot caso nenhuma capa do SteamGridDB esteja disponível.
4. Faz consulta pelo nome ao catálogo público da Steam (sem chave), aceitando apenas resultados com título equivalente.
5. Se nenhum serviço fornecer uma imagem válida, mostra as iniciais do jogo em vez de um espaço vazio.

Os arquivos baixados ficam no cache privado do aplicativo. Consultas simultâneas iguais são compartilhadas e buscas que falham não se repetem durante 1 hora enquanto o aplicativo está aberto. Fotos editadas ou capas escolhidas manualmente têm prioridade. Uma chave SteamGridDB nova limpa os estados de falha para tentar novamente.

## Configuração

No Android: **Sync → Configurar SteamGridDB** e insira a API Key obtida em https://www.steamgriddb.com/profile/preferences/api. A chave é opcional, mas amplia muito a cobertura de jogos fora da Steam, como Genshin Impact.

**Privacidade:** títulos locais do Android, jogos manuais e visuais privados Ren'Py não são consultados automaticamente por nome nos serviços de catálogo. O recurso de capa selecionada manualmente continua disponível.

## Limitações

Nem todo jogo tem capa cadastrada; também podem ocorrer limites de API, erros de conexão e diferenças de nomes entre catálogos. Os logos públicos da Riot são alternativas visuais, não capas verticais oficiais. O Ludex não inventa uma imagem de outro jogo apenas por semelhança aproximada.
