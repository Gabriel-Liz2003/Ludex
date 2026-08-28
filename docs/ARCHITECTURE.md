# Arquitetura do Ludex

O Ludex usa Tauri 2 com backend Rust, UI TypeScript/Vite e SQLite local-first. O frontend nunca é a fonte de verdade para biblioteca, instalações ou tempo jogado.

## Identidade e instalações

`games` representa a identidade global do jogo. `installations` representa uma cópia jogável proveniente de um provider. `external_ids` relaciona IDs de providers à identidade global sem acoplar `Game` à Steam, Epic, GOG ou outro launcher.

A deduplicação atual é conservadora: primeiro usa `(provider, external_id)` e depois tenta uma correspondência única por título normalizado. Ambiguidade preserva registros separados.

## Steam

`providers/steam.rs` detecta a Steam pelo Registro do Windows e por caminhos conhecidos, lê `libraryfolders.vdf`, processa `appmanifest_*.acf` e gera instalações independentes. DLCs, runtimes, redistributables, Proton e ferramentas reconhecíveis são filtrados.

A sincronização é idempotente porque a instalação Steam usa ID estável `steam:<AppID>` e o par `(provider, external_id)` possui índice único.

## Launch e ProcessMonitor

Providers não são responsáveis por contabilizar horas. `launch_game` resolve uma instalação e usa o mecanismo específico do provider: Steam URI para Steam e `Command` direto para jogos manuais.

`process_monitor.rs` é independente dos providers. Para Steam, o Ludex captura os PIDs existentes antes do launch e só aceita processos novos cujo executável esteja dentro do diretório de instalação, ignorando processos genéricos da Steam e redistributables.

## Sessões e recuperação

Uma `play_session` só começa depois da confirmação do processo. Durante execução, um heartbeat atualiza `last_seen_at`, PID e caminho. O encerramento deriva a duração de `started_at` e `ended_at`.

Ao iniciar, sessões sem `ended_at` são verificadas. Se o PID/caminho ainda existir, o monitor continua. Caso contrário, a sessão é encerrada usando o último heartbeat e um limite defensivo de 18 horas para impedir durações absurdas em dados corrompidos.

As estatísticas exibidas são derivadas de `play_sessions`; `games.total_seconds` permanece apenas por compatibilidade com a migration inicial e não é a fonte de verdade.

## Concorrência

Scans Steam são executados com `spawn_blocking`, fora da UI. O monitor de processos roda em threads de baixa frequência: 2 s durante a janela de detecção e 5 s durante uma sessão ativa. Não existe polling global contínuo para descobrir jogos iniciados fora do Ludex.

## Limitação atual

Sessões iniciadas diretamente pela Steam, sem passar pelo Ludex, ainda não são detectadas. A arquitetura deixa `ProcessMonitor` separado para que um watcher orientado a eventos ou de baixo custo possa ser adicionado depois sem acoplar essa responsabilidade ao Steam provider.
