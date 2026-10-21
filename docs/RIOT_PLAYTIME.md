# Importação de horas — Riot Games

O Ludex permite registrar o tempo histórico estimado de **League of Legends** (WOL.GG) e **VALORANT** (Tracker.gg), sem solicitar dados de autenticação nem utilizar APIs privadas ou scraping.

## Android

1. Abra a aba **Sincronização**.
2. Na seção **Riot Games · LoL e VALORANT**, toque em **Importar horas de LoL / VALORANT**.
3. Selecione o jogo, consulte as horas no site correspondente e informe o **total histórico acumulado** em horas (por exemplo, `1250,5`).
4. Opcionalmente, informe a URL HTTPS de seu perfil no domínio correspondente.
5. Salve; os jogos aparecem na biblioteca geral e no filtro **Riot Games**.

## Windows

Abra **Configurações → Riot Games → Importar horas Riot** e repita os passos. Os sites podem ser abertos diretamente na janela.

## Regras dos dados

- A importação é **manual**: o Ludex não acessa os perfis, coleta dados por scraping nem se autentica nas contas Riot, WOL.GG ou Tracker.gg.
- As horas vêm de serviços externos e são **estimativas**. Em VALORANT, verifique o período **All Acts** e considere todos os modos de jogo ao obter o total.
- Uma importação é o total atual, não uma quantidade incremental. A mesma fonte/jogo é atualizada, não somada repetidamente.
- IDs estáveis: `riot:lol`, `riot:valorant`. Fontes: `riot-wolgg` e `riot-trackergg`. Os registros são armazenados em `imported_playtime`, com instante da última alteração.
- Os totais e as identificações das fontes acompanham o **JSON de sincronização** entre Windows e Android. O link de perfil, por ser um dado opcional local, não é exportado no bundle.
- Na importação de bundles, totais históricos da Riot mais recentes podem substituir valores anteriores, inclusive quando menores. Outros provedores mantêm suas regras de conciliação.
- A importação não cria sessões fictícias, não duplica tempos de outras plataformas e não modifica credenciais ou cookies.
- Para corrigir horas, reabra o formulário e substitua pelo novo total.

## Limitações

O Tracker Network não disponibiliza uma API pública de estatísticas de VALORANT. A sincronização automática dessas horas não está implementada nem é anunciada como disponível. A API oficial da Riot também não equivale a uma consulta irrestrita de horas históricas completas.
