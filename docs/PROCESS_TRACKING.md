# Process tracking

## Separação de responsabilidades

Providers descobrem/lançam; `ProcessMonitor` decide se existe um processo de jogo confiável; `sessions.rs` controla lifecycle/persistência. Isso evita adaptar a contagem de horas a cada loja.

## Snapshot e identidade

`ProcessInfo` inclui PID, PPID, nome, executable/path, command line, start time e memória. Identidade usa PID + start time + executable para reduzir risco de PID reuse.

## Scoring

Evidências positivas incluem processo novo, executable dentro da instalação, executable conhecido, relação com root/descendente, janela temporal de launch e AppID. Steam/global launchers, updaters, redistributables e helpers recebem penalidades. Anti-cheat e launcher não abrem sessão sozinhos.

Launch iniciado no Ludex e discovery externo possuem thresholds diferentes; externo é mais conservador.

## Sessão multiprocesso

A sessão só é persistida após estabilização. Durante tracking, membros relacionados são atualizados. Se launcher pai morrer e o jogo filho continuar, a sessão permanece. Se somente launcher/anti-cheat persistir, o tracker confirma ausência do jogo antes de encerrar.

## Recovery

Sessões abertas ao reiniciar o Ludex são comparadas com processo + start identity. Quando o processo desapareceu, duração usa último heartbeat com limite defensivo de 18h.

## Custo

Polling é adaptativo: rápido apenas na janela curta após launch; reduzido durante sessão e ainda menor no discovery externo/idle. Não existe scan de alta frequência permanente.

## Integration harness

`script/add-process-harness.py` injeta no test binary Windows um cenário real com executáveis copiados/renomeados para `FakeLauncher.exe`, `EasyAntiCheat.exe`, `FakeGame.exe` e `ThirdPartyLauncher.exe`. O CI permanente executa `fake_process_tree_survives_launcher_exit`, cobrindo launcher que termina, filho que permanece, anti-cheat e launcher persistente com deadlines em vez de sleeps longos frágeis.
