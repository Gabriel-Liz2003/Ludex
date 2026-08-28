# Arquitetura do Ludex 0.9

## Princípios

Ludex é local-first, offline-first e orientado a providers. O Desktop usa Tauri 2/Rust + TypeScript/Vite; o Android é nativo e compartilha o protocolo de dados, não uma UI WebView pesada. SQLite é a fonte de verdade no Desktop.

## Domínio

- `games`: identidade global e preferências do usuário.
- `installations`: cópias jogáveis por provider/dispositivo.
- `external_ids`: identidade externa estável por provider.
- `play_sessions`: sessões medidas pelo Ludex.
- `imported_playtime`: tempo histórico confiável importado, separado das sessões.
- `game_metadata`: metadata/cache com flag de prioridade manual.
- `collections` / `collection_games`: organização do usuário.
- `emulators` / `roms`: configuração e biblioteca emulada.
- `achievements`: modelo genérico, preenchido somente por fontes legítimas.
- tabelas de processo: estado e membros de sessões multiprocesso.
- tabelas de sync/save backup: IDs estáveis, timestamps e histórico de backups.

Migrations são aditivas e compatíveis com bancos anteriores. O schema não depende de apagar/recriar a base.

## Providers

`LibraryProvider`/`ProviderScan` isolam descoberta e parsing. O core importa `ScannedInstallation`, resolve identidade/deduplicação e persiste `Installation`. Launch é resolvido por provider, mas tracking nunca fica dentro do provider.

Isso permite reutilizar o mesmo fluxo para Steam, Epic, GOG, Xbox/MS Store, EA, Ubisoft, Battle.net e manual.

## Deduplicação

Ordem de confiança:

1. `(provider, external_id)` já conhecido;
2. identidade externa/metadata estável;
3. correspondência única por título normalizado;
4. baixa confiança não é mergeada automaticamente.

Merge e split manual permitem corrigir casos ambíguos sem destruir sessões ou instalações.

## Process tracking

`ProcessMonitor` é independente de lojas. Ele captura snapshots, PPID/árvore, executable/path/start time/memória e classifica processos como jogo, launcher, anti-cheat ou ignorado. `ProcessCandidateScorer` centraliza evidências; discovery iniciado pelo Ludex e discovery externo usam thresholds distintos.

Uma sessão começa somente após processo confiável e persistente. O tracker segue filhos/descendentes, troca o processo principal quando necessário, ignora launcher persistente sem jogo e termina após confirmação de ausência de processo de jogo. Heartbeat e recovery protegem contra crash do Ludex e PID reuse.

## Metadata

`MetadataProvider` desacopla aquisição de metadata. A implementação local da Steam lê somente artwork já presente em `appcache/librarycache`. Atualizações automáticas respeitam `manual=1`, portanto uma edição do usuário não é sobrescrita.

## Emulação

`EmulatorAdapter` transforma ROM + configuração em `Command` e argumentos, sem shell concatenado. Scanner recursivo persiste hash SHA-256 e não associa plataforma somente por extensão ambígua. ROM vira uma instalação jogável `emulation` e usa o mesmo tracking de sessão.

## Sync

O protocolo JSON é versionado. Jogos, instalações, sessões, metadata, coleções e playtime importado usam IDs estáveis. Sessões já existentes não são recriadas; campos com `updated_at` preservam versões mais novas. O transporte 0.9 é arquivo explícito, utilizável entre Desktop e Android sem servidor proprietário.

## Android

O Android mantém banco local próprio, detecta apps por PackageManager, usa `UsageStatsManager` para foreground e exporta/importa o mesmo envelope de sync. Nenhum Accessibility Service ou captura de tela é usado para contabilização.

## Performance

SQLite usa WAL, índices para título/provider/sessões e queries agregadas. A suíte contém teste sintético com milhares de jogos. UI usa imagens apenas quando necessárias e o cache de metadata armazena paths/URLs, não bitmaps no banco.

## Segurança e erros

- argumentos de executável são tokenizados;
- providers geram seus próprios URIs/AUMIDs;
- manifests inválidos retornam erro ou são ignorados com diagnóstico;
- secrets não são hardcoded;
- restore de saves não sobrescreve destino existente;
- operações de filesystem validam existência e retornam mensagens ao usuário;
- integrações de conta não usam endpoints não oficiais inventados.
