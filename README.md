# Ludex 0.9.0

Ludex é um launcher e biblioteca universal de jogos **local-first**, com aplicativo Windows e Android. Ele reúne instalações de múltiplos launchers, jogos manuais e ROMs em uma identidade de jogo única, acompanha sessões e playtime e continua utilizável offline.

## Funcionalidades

### Windows

- biblioteca em grid ou lista, busca, filtros, favoritos, recentes e status do usuário;
- detalhes do jogo, múltiplas instalações e escolha da cópia a iniciar;
- cadastro manual com executável, working directory e argumentos;
- providers para Steam, Epic Games Store, GOG, Xbox/Microsoft Store, EA App, Ubisoft Connect e Battle.net dentro do que pode ser descoberto de forma sustentável no Windows;
- deduplicação conservadora `Game` / `Installation`, com merge e split manual;
- ProcessMonitor independente do provider, árvore de processos, scoring, anti-cheat/launcher filtering, heartbeat, recuperação após crash e descoberta de sessões externas quando há confiança suficiente;
- playtime medido pelo Ludex separado de playtime histórico importado;
- coleções, metadata editável, artwork local da Steam quando disponível, achievements genéricos e estatísticas agregadas;
- emulação integrada, scanner recursivo de ROMs, presets de emuladores, launch direto e backup seguro de saves configurados;
- backup do banco, export/import JSON versionado e sincronização por arquivo;
- diagnóstico de providers, banco e sessões.

### Android

O app nativo em `android/` não é apenas um placeholder. Ele compila para APK e oferece:

- detecção de aplicativos instalados com launcher activity;
- classificação por `ApplicationInfo.CATEGORY_GAME`, com fallback manual;
- abertura de jogos Android;
- leitura de uso via `UsageStatsManager` com fluxo explícito para conceder acesso de uso;
- cálculo incremental de períodos em foreground, sem Accessibility Service ou captura de tela;
- biblioteca, pesquisa, detalhes e estatísticas;
- export/import de sync JSON por seletor de documentos do Android.

## Providers Windows

| Provider | Descoberta/importação | Launch | Tracking Ludex |
| --- | --- | --- | --- |
| Steam | Registro, `libraryfolders.vdf`, `appmanifest_*.acf` | `steam://rungameid` | Sim |
| Epic | manifests `.item` locais | executável/URI disponível no manifest | Sim |
| GOG | arquivos `goggame-*.info` e instalações DRM-free | direto quando executável está disponível | Sim |
| Xbox / Microsoft Store | pacotes/AUMID expostos pelo Windows | `shell:AppsFolder` | Sim quando o processo pode ser associado |
| EA App | dados/instalações locais detectáveis | mecanismo local configurado/encontrado | Sim |
| Ubisoft Connect | Registry/instalações locais | executável/launcher configurado | Sim |
| Battle.net | configuração local detectável | somente quando há launch target confiável | Sim quando associável |
| Manual | configurado pelo usuário | executável direto | Sim |

Reimportar um provider marca instalações ausentes como desinstaladas sem apagar a identidade do jogo, sessões ou histórico.

## Emulação

A arquitetura é genérica e possui presets para RetroArch, Dolphin, PCSX2, RPCS3, PPSSPP, DuckStation, Cemu, Ryujinx/alternativas configuráveis, melonDS e mGBA. O usuário fornece os próprios emuladores, ROMs e BIOS legalmente obtidos.

O scanner suporta recursão, hash SHA-256, deduplicação e formatos comuns como `iso`, `chd`, `cue`, `bin`, `rvz`, `wbfs`, `gba`, `gbc`, `nds`, `3ds`, `nsp`, `xci`, `nes`, `snes`, `n64`, `z64`, `v64` e `pbp`. Extensões ambíguas não são usadas sozinhas para inferir plataforma.

## Metadata

`MetadataProvider` separa a origem da metadata do domínio principal. A 0.9.0 inclui metadata manual com prioridade e provider local de artwork da Steam usando apenas o cache existente no computador. Overrides manuais não são sobrescritos por refresh automático. O schema já mantém capa, hero, descrição, developer, publisher, data, gêneros, plataformas e screenshots/cache.

## Sync, backup e offline

Ludex não exige conta ou servidor proprietário. O formato JSON de sync é versionado e usa IDs estáveis. Ele transporta jogos, instalações, sessões, metadata, favoritos/status, coleções e playtime importado, evitando recriar sessões com IDs já existentes. Campos sincronizáveis possuem timestamps para evitar sobrescrita cega de versões mais novas.

O Desktop continua navegável, pesquisável e capaz de lançar jogos, emular, registrar sessões, editar metadata/coleções e mostrar estatísticas sem internet. O Android também mantém os dados locais e usa arquivo para troca de sync.

## Contas PlayStation e Xbox

Ludex **não inventa APIs de conta**. As APIs oficiais Xbox Live pesquisadas exigem contexto de título/XSTS/assinatura e o endpoint oficial de histórico disponível publicamente é orientado a títulos com progresso de achievements, não a uma biblioteca completa arbitrária. A documentação pública PlayStation encontrada não fornece uma API geral de biblioteca de usuário para launchers de terceiros. Por isso a 0.9.0 mantém essas bibliotecas de conta em fallback manual/importável, enquanto o provider Xbox PC local funciona normalmente para instalações expostas pelo Windows.

## Privacidade e segurança

- dados locais em SQLite;
- sem telemetria obrigatória, anúncios ou venda de dados;
- launch usa `Command` com argumentos tokenizados, não concatenação de shell para executáveis manuais;
- URIs de providers são produzidas por código específico do provider;
- manifests corrompidos são ignorados/reportados em vez de causar panic no scan;
- backups de saves recusam restauração destrutiva sobre destino existente;
- segredos/tokens não são hardcoded.

## Build

### Windows

Pré-requisitos: Node.js 22+, Rust stable e dependências do Tauri 2 para Windows.

```bash
npm install
npm run build
cargo fmt --manifest-path src-tauri/Cargo.toml -- --check
cargo check --manifest-path src-tauri/Cargo.toml
cargo test --manifest-path src-tauri/Cargo.toml
npm run tauri -- build --bundles nsis
```

O instalador é gerado em `src-tauri/target/release/bundle/nsis/`.

### Android

Pré-requisitos: JDK 17 e Android SDK.

```bash
gradle -p android :app:assembleDebug
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions executa frontend, Rust, harness real de processos no Windows, Android e empacotamento NSIS, publicando os artifacts `ludex-android-debug` e `ludex-windows`.

## Desenvolvimento

```bash
npm install
npm run tauri dev
```

Estrutura principal:

```text
src/                              UI Desktop
src-tauri/src/db.rs               schema base e biblioteca
src-tauri/src/product.rs          domínio de produto/migrations/stats/sync
src-tauri/src/providers/          providers Windows
src-tauri/src/process_monitor.rs  descoberta e classificação de processos
src-tauri/src/sessions.rs         lifecycle de sessões
src-tauri/src/emulation/          adapters e argumentos
src-tauri/src/metadata.rs         metadata providers
android/                          aplicativo Android nativo
docs/                             documentação técnica
```

## Limitações conhecidas

- CI valida manifests e providers com fixtures/dados sintéticos; ele não possui contas ou bibliotecas reais de todos os launchers comerciais.
- Apps MSIX protegidos podem ocultar executáveis e exigir launch por AUMID; associação de processo depende do que o Windows expõe.
- Alguns launchers globais/anti-cheats podem impedir associação confiável; nesses casos o Ludex prefere não contar a sessão a registrar horas falsas.
- Metadata externa que exige credencial de serviço não possui segredo embutido; a 0.9.0 usa cache Steam local e edição manual.
- PlayStation/Xbox account library permanece limitada pelas APIs oficiais disponíveis para aplicativos de terceiros.

Mais detalhes: `docs/ARCHITECTURE.md`, `docs/PROVIDERS.md`, `docs/EMULATION.md`, `docs/SYNC.md` e `docs/PROCESS_TRACKING.md`.

## Atualizações Desktop

A partir da versão 0.9.1, o Desktop Windows consulta as Releases oficiais deste repositório, baixa o instalador da versão mais recente dentro do próprio Ludex e inicia a atualização. O workflow `release.yml` publica automaticamente uma nova release quando `main` recebe uma versão ainda não publicada.

A versão 0.9.1 também enriquece a importação Steam sem exigir chave de API: o Ludex lê o `localconfig.vdf` da conta Steam local mais recentemente usada para importar `Playtime` e usa primeiro o artwork local do cache, com fallback para o CDN oficial da Steam.

