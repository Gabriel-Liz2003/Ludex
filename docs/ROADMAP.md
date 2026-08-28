# Roadmap incremental

## Fase 0 — Fundação (esta branch)

- [x] Tauri 2 + TypeScript/Vite
- [x] SQLite local-first
- [x] Cadastro manual de jogos
- [x] Biblioteca pesquisável
- [x] Schema para instalações, sessões, coleções, ROMs e emuladores
- [x] Contratos `LibraryProvider` e `EmulatorAdapter`
- [x] Detecção inicial da instalação da Steam no Windows
- [ ] CI validado em runner Windows

## Fase 1 — PC funcional

- [ ] Parsear `libraryfolders.vdf` e `appmanifest_*.acf`
- [ ] Steam: detectar bibliotecas e instalações
- [ ] Epic: ler manifests locais
- [ ] GOG: detectar instalações legítimas
- [ ] Xbox/Microsoft Store: investigar APIs/manifestos suportados
- [ ] EA/Ubisoft/Battle.net/itch.io
- [ ] Deduplicação canônica
- [ ] Launch + argumentos por instalação
- [ ] Monitor de processo e árvore de filhos
- [ ] Sessões iniciadas fora do Ludex com baixo impacto

## Fase 2 — Emulação

- [ ] CRUD de emuladores
- [ ] Detecção de Dolphin, PCSX2, RPCS3, PPSSPP, DuckStation, RetroArch etc.
- [ ] Scanner de ROMs por assinatura/extensão + confirmação em ambiguidades
- [ ] Metadata e capas com cache
- [ ] Launch direto ROM → emulador
- [ ] Sessão de jogo emulado
- [ ] Diretórios de saves + backup seguro opcional

## Fase 3 — Android

- [ ] Inicializar target Android Tauri
- [ ] Enumerar apps/jogos conforme regras do Android
- [ ] Launch de package
- [ ] UsageStatsManager com tela de consentimento
- [ ] Somente foreground + tela ativa
- [ ] Persistência e merge de sessões

## Fase 4 — Sync

- [ ] Export/import versionado
- [ ] Sync por arquivo
- [ ] Descoberta LAN
- [ ] Servidor self-hosted opcional
- [ ] Resolução determinística de conflitos

## Fase 5 — Integrações externas

- [ ] Metadata via fontes permitidas
- [ ] Microsoft/Xbox OAuth e dados oficialmente disponíveis
- [ ] PSN apenas onde houver API legítima e sustentável
- [ ] Achievements/troféus quando suportados

## Fase 6 — Escala e release

- [ ] Biblioteca sintética com 10.000+ jogos
- [ ] Lazy loading de imagens
- [ ] Índices e paginação
- [ ] Windows installer
- [ ] APK/AAB Android
- [ ] Testes offline e de regressão
