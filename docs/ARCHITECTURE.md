# Arquitetura do Ludex

## Princípios

- **Local-first:** SQLite é a fonte primária de verdade; conta e servidor não são obrigatórios.
- **Leve:** Tauri 2 usa WebView do sistema em vez de empacotar Chromium/Electron.
- **Modular:** cada loja/plataforma implementa `LibraryProvider`; cada emulador implementa `EmulatorAdapter`.
- **Sem APIs inventadas:** integrações externas devem declarar capacidades e limitações reais.
- **Offline-first:** biblioteca, busca, sessões e emulação devem continuar disponíveis sem internet.

## Camadas

1. `src/`: interface do usuário responsiva compartilhada entre desktop e Android.
2. `src-tauri/src/models.rs`: contratos de domínio.
3. `src-tauri/src/db.rs`: persistência SQLite e migrations iniciais.
4. `src-tauri/src/providers/`: Steam, Epic, GOG, Xbox, PSN, Android e providers futuros.
5. `src-tauri/src/emulation/`: abstração de emuladores, ROMs e montagem de comandos.
6. Futuro `sessions/`: monitoramento de processo no Windows e foreground tracking no Android.
7. Futuro `sync/`: LAN, arquivo e servidor self-hosted opcional.

## Modelo de dados inicial

- `games`: identidade canônica do jogo; não representa instalação individual.
- `installations`: fontes/executáveis associados a um jogo; permite deduplicação entre lojas.
- `play_sessions`: sessões individuais para estatísticas reproduzíveis.
- `collections`/`collection_games`: organização do usuário.
- `emulators`: configuração genérica, sem lista fixa embutida.
- `roms`: arquivo do usuário associado a jogo/plataforma/emulador.
- `settings`: preferências simples; segredos não devem ser colocados aqui.

## Deduplicação

Providers nunca devem simplesmente inserir um novo `game` para cada launcher. O pipeline de importação deve normalizar títulos, usar IDs externos quando confiáveis e produzir candidatos para confirmação quando houver ambiguidade.

## Rastreamento de tempo

No Windows, o tracker deverá observar a árvore de processos do jogo e persistir uma `play_session` apenas enquanto o executável do jogo estiver ativo. Launchers não contam como sessão. No Android, o tracker será implementado com APIs oficiais de uso/foreground e consentimento explícito.

## Segurança

Tokens de serviços externos devem usar Windows Credential Manager/Android Keystore (via plugin seguro ou camada nativa). Nunca persistir tokens em texto puro no SQLite.
