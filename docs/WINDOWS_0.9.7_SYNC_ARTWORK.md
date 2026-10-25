# Windows 0.9.7 — sincronização de provedores e capas

## Correções implementadas

### Sincronização Windows
- Nova área **Sincronizar launchers deste PC** na aba Sincronização.
- Ações para **Steam, Epic Games Store, GOG, Xbox/Microsoft Store/Game Pass local, EA App, Ubisoft Connect e Battle.net**. É possível acionar um launcher de cada vez ou **Sincronizar todos**.
- Cada fonte exibe detecção, quantidade de instalações, data do último sincronismo e resultado/erro individual; a ausência de um launcher não aborta as outras fontes.
- O aplicativo **não remove instalações importadas anteriormente** se não conseguir encontrar o launcher.
- Fluxos separados e claramente rotulados para **Nintendo Switch, PSN e Xbox histórico** via JSON do Android, Steam Web API e horas manuais da **Riot**. Essas opções não são falsamente apresentadas como login automático.
- Nenhuma promessa de importação remota automática de todo o histórico de Epic, EA, Ubisoft, Battle.net e GOG: o scanner local importa instalações, e o Ludex continua medindo sessões que consegue acompanhar.

### Carregamento das capas
- Carrega o índice de capas de uma vez pelo SQLite (`artwork_catalog`), em vez de abrir/migrar o banco para **cada card**.
- Resolve as imagens com no máximo **3 tarefas simultâneas**, sem bloquear a thread de UI do Tauri mesmo quando o servidor Steam demora.
- Desconecta o observador anterior e cancela itens antigos da fila ao alterar filtros/páginas.
- Cache limitado de URLs e supressão temporária de buscas que falharam. Quando não houver capa, preserva as iniciais do título.
- Ao clicar em um jogo, atualiza a seleção sem recriar a grade inteira (antes cada clique disparava novas buscas e imagens).
- Nenhuma limpeza destrutiva ou alteração no tempo de jogo para essa correção.

## Verificação

CI: `npm run build`, `cargo fmt --check`, `cargo check`, `cargo test`, empacotamento NSIS Windows e build Android, executados por GitHub Actions.

## Limites

Os provedores locais não importam jogos desinstalados e todo o tempo histórico sem API confiável; PlayStation e Nintendo dependem do bundle exportado do Android. A sincronização entre dispositivos continua sendo por JSON explícito (sem serviço LAN automático).
