# Importação completa da biblioteca Steam

O provider local da Steam detecta jogos instalados por `libraryfolders.vdf` e `appmanifest_*.acf`. Isso continua sendo a fonte de verdade para estado instalado, pasta e launch.

A partir do Ludex 0.9.2 existe uma segunda sincronização opcional, de conta, para importar também jogos Steam que não estão instalados no computador.

## Fluxo

1. O Ludex detecta a conta Steam usada recentemente em `Steam/config/loginusers.vdf` e obtém o SteamID64.
2. O usuário fornece sua própria Steam Web API key em **Configurações → Conta Steam**.
3. A chave é protegida para o usuário atual do Windows usando DPAPI.
4. O Ludex chama `IPlayerService/GetOwnedGames` com `include_appinfo=true` e `include_played_free_games=true`.
5. Cada AppID é associado a `external_ids(provider='steam')`.
6. Jogos não instalados recebem uma `Installation` Steam estável com `installed=0`; isso permite uma identidade única do jogo sem fingir que há arquivos locais.
7. O tempo histórico retornado pela Steam é salvo em `imported_playtime`, separado das sessões realmente medidas pelo Ludex.

Quando um jogo posteriormente é instalado, o provider local atualiza a mesma Installation `steam:<appid>` com pasta/executável/estado instalado. O histórico e a identidade do Game não são apagados.

## Privacidade e limitações

- A API key não é hardcoded nem enviada para o frontend depois de salva.
- A Steam Web API pode limitar dados de acordo com as permissões/visibilidade da conta e com as regras do endpoint.
- `GetOwnedGames` representa jogos da conta retornados pela API; compartilhamento familiar e outros direitos temporários podem não equivaler a propriedade e devem ser tratados separadamente caso a Steam exponha uma fonte oficial adequada.
- Sem API key, o Ludex continua funcionando normalmente com a biblioteca local/instalada e com o playtime recuperável do cache local.
