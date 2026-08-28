# Roadmap do Ludex

## Concluído nesta etapa

- Steam detectada por Registro/caminhos conhecidos no Windows.
- Parser de `libraryfolders.vdf` e `appmanifest_*.acf` com fixtures.
- Importação idempotente da biblioteca Steam.
- Separação entre `Game`, `Installation` e IDs externos.
- Deduplicação conservadora por ID externo e título normalizado único.
- Launch Steam via `steam://rungameid/<AppID>`.
- Launch de jogos manuais com executável, diretório de trabalho e argumentos.
- `ProcessMonitor` independente do provider.
- Sessões reais iniciadas somente após processo confirmado.
- Heartbeat, encerramento e recuperação após crash.
- Estatísticas de total, 14 dias, 30 dias, última sessão, média e contagem.
- Estado `EM JOGO` e sessões recentes na interface.
- Painel Configurações → Bibliotecas → Steam.

## Próximos incrementos

1. Descoberta de sessões Steam iniciadas fora do Ludex com mecanismo orientado a eventos/baixo custo.
2. Resolução de executáveis Steam com metadados adicionais para casos em que o processo final executa fora da pasta principal.
3. Capa/background e metadados externos com cache local.
4. Epic e GOG usando o mesmo contrato de instalações/IDs externos.
5. Importação e launch de ROMs com adapters reais.
6. Sincronização local-first Desktop ↔ Android.
7. Android UsageStatsManager e geração de APK.

## Limitações conhecidas

- O ambiente de CI não possui uma instalação real da Steam; parsing e deduplicação usam fixtures/testes automatizados.
- Alguns jogos com launchers externos/anti-cheat que executem fora do diretório do jogo podem exigir regras específicas de associação de processo.
- Tempo histórico fornecido pela Steam ainda não é importado; o total exibido é tempo medido localmente pelo Ludex.
