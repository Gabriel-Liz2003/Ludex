# Ludex

Ludex é uma biblioteca e launcher universal de jogos **local-first**, com aplicativo Windows e Android. O objetivo é reunir jogos instalados em diferentes launchers, títulos importados, ROMs, sessões e playtime em uma única biblioteca sem depender de um servidor proprietário para funcionar.

**Versões atuais do projeto:**
- Desktop Windows: **0.9.5**
- Android: **0.9.13**

## Destaques

- biblioteca unificada para PC e Android;
- importação de jogos de múltiplos providers;
- deduplicação conservadora de títulos;
- tracking próprio de sessões/playtime;
- importação de playtime histórico quando a plataforma permite;
- emulação e ROMs;
- sync por JSON;
- funcionamento local/offline;
- atualizações automáticas no Desktop e Android;
- integração Android com GameNative e Shizuku.

## Windows

O aplicativo Desktop usa **Tauri 2 + Rust + TypeScript/Vite + SQLite**.

### Biblioteca

- visualização em grid/lista;
- busca, filtros, favoritos, recentes e status;
- detalhes do jogo;
- múltiplas instalações para a mesma identidade de jogo;
- escolha de qual instalação iniciar;
- jogos cadastrados manualmente com executável, diretório e argumentos;
- merge/split manual para corrigir deduplicações.

### Providers

| Provider | Descoberta/importação | Launch | Tracking Ludex |
| --- | --- | --- | --- |
| Steam | Registro, `libraryfolders.vdf`, `appmanifest_*.acf` e biblioteca de conta opcional | `steam://rungameid` | Sim |
| Epic Games Store | manifests `.item` | executável/URI do manifest | Sim |
| GOG | `goggame-*.info` e instalações locais | direto quando disponível | Sim |
| Xbox / Microsoft Store | pacotes/AUMID expostos pelo Windows | `shell:AppsFolder` | Quando associável |
| EA App | dados/instalações locais detectáveis | mecanismo local disponível | Sim |
| Ubisoft Connect | Registry/instalações locais | launcher/executável configurado | Sim |
| Battle.net | configuração local detectável | apenas com alvo confiável | Quando associável |
| Manual | configurado pelo usuário | executável direto | Sim |

Reimportar um provider pode marcar instalações ausentes como desinstaladas sem apagar a identidade do jogo, sessões ou histórico.

### Steam

O Ludex consegue:

- importar instalações Steam locais;
- identificar a conta Steam utilizada localmente;
- ler playtime do `localconfig.vdf`;
- usar artwork local quando disponível;
- usar artwork moderno com fallback seguro;
- importar opcionalmente a biblioteca completa da conta via `IPlayerService/GetOwnedGames`;
- armazenar credenciais sensíveis no Windows usando DPAPI.

### Tracking de sessões

O tracking é independente do provider e inclui:

- árvore de processos;
- scoring de candidatos;
- filtro de launchers/anti-cheat;
- heartbeat;
- recuperação após crash;
- descoberta de sessões iniciadas fora do Ludex quando há confiança suficiente;
- separação entre playtime medido pelo Ludex e playtime histórico importado.

## Android

O aplicativo Android em `android/` é nativo e utiliza Java + AndroidX/Material.

### Jogos Android

- detecção de jogos instalados;
- classificação via `ApplicationInfo.CATEGORY_GAME`;
- abertura direta dos aplicativos;
- leitura de uso via `UsageStatsManager`;
- importação de tempo em foreground;
- ajuste manual de baseline para reconciliar históricos incompletos do Android;
- favoritos e status;
- biblioteca e busca;
- export/import de sync JSON.

O Android não oferece uma API pública geral para aplicativos de terceiros lerem o histórico oficial completo mostrado pela Play Store/Play Games. Por isso o Ludex usa Usage Access e permite calibrar manualmente o total quando necessário.

## GameNative

O Ludex possui integração específica com o **GameNative** no Android.

### Importação por atalhos

O GameNative publica atalhos Android para os jogos. O Ludex pode usar **Shizuku** para consultar esses shortcuts e importar somente os jogos que realmente possuem um atalho criado.

O scanner reconhece atalhos no formato:

```text
game_<appid>
```

e tenta recuperar:

- título;
- `app_id`;
- `game_source`;
- provider/origem.

Exemplos já testados incluem jogos como Hades, GRIS e Kingdom Hearts.

### Launch direto

Quando há metadata suficiente, o Ludex inicia o jogo diretamente pelo Intent exposto pelo GameNative:

```text
action: app.gamenative.LAUNCH_GAME
extra: app_id
extra: game_source
```

Assim, o botão **Jogar** abre o título diretamente no GameNative em vez de apenas abrir o launcher.

### Artwork

Jogos GameNative associados à Steam usam o App ID detectado para carregar artwork real da Steam.

O Android:

- tenta primeiro capas de biblioteca;
- usa header como fallback;
- mantém cache local em `files/artwork`;
- usa o ícone do GameNative como placeholder durante o carregamento;
- evita trocar capas entre cards reciclados do RecyclerView.

Providers GameNative que não sejam Steam ainda podem usar o ícone do GameNative como fallback.

### Shizuku

O Shizuku é usado para consultar informações do sistema sem exigir que o Ludex tenha privilégios elevados próprios.

A integração inclui:

- detecção do binder do Shizuku;
- listener de ciclo de vida do binder;
- solicitação de autorização;
- consulta de shortcuts via serviço do Android;
- fallback para armazenamento quando aplicável.

**Limitação:** Shizuku iniciado apenas via ADB roda normalmente como usuário `shell` e não consegue acessar livremente `/data/user/0/app.gamenative`. A integração por shortcuts existe justamente para evitar depender dessa pasta privada.

## Emulação

A arquitetura Desktop possui presets/adapters para emuladores como:

- RetroArch;
- Dolphin;
- PCSX2;
- RPCS3;
- PPSSPP;
- DuckStation;
- Cemu;
- Ryujinx/alternativas configuráveis;
- melonDS;
- mGBA.

No Android, emuladores instalados também podem aparecer em uma área própria da interface.

O usuário deve fornecer legalmente seus próprios emuladores, ROMs e BIOS.

O scanner Desktop suporta formatos como:

`iso`, `chd`, `cue`, `bin`, `rvz`, `wbfs`, `gba`, `gbc`, `nds`, `3ds`, `nsp`, `xci`, `nes`, `snes`, `n64`, `z64`, `v64` e `pbp`.

## Metadata e artwork

A camada de metadata é separada do domínio principal.

O projeto mantém suporte para:

- capa;
- hero;
- descrição;
- developer;
- publisher;
- data;
- gêneros;
- plataformas;
- screenshots;
- cache local;
- overrides manuais.

Overrides feitos pelo usuário têm prioridade sobre refresh automático.

## Sync, backup e offline

O Ludex não exige uma conta própria.

O formato JSON de sync é versionado e pode transportar:

- jogos;
- instalações;
- sessões;
- playtime importado;
- favoritos;
- status;
- metadata compatível.

O Desktop continua utilizável offline para navegação, launch, tracking e operações locais. O Android também mantém sua biblioteca localmente.

## Atualizações

### Desktop

O Desktop Windows consulta as Releases do GitHub, baixa o instalador mais recente e inicia a atualização.

O workflow `release.yml` publica releases Desktop quando uma nova versão válida chega ao `main`.

### Android

A partir da linha **0.9.12+**, o Android possui fluxo de atualização assinado.

O processo é:

1. o app consulta Releases do GitHub;
2. procura tags `android-v<versão>`;
3. seleciona a versão válida mais recente;
4. baixa o APK;
5. baixa o arquivo SHA-256;
6. verifica a integridade localmente;
7. abre o instalador do Android.

O workflow `.github/workflows/android-release.yml`:

- exige uma chave persistente;
- gera APK release;
- valida assinatura com `apksigner`;
- publica APK + SHA-256;
- cria releases no formato `android-vX.Y.Z`.

Os secrets necessários são:

```text
ANDROID_KEYSTORE_BASE64
ANDROID_STORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

Mais detalhes em `android/SIGNING.md`.

### Migração de builds debug antigas

Builds antigas do CI eram assinadas com chave debug. Android não permite atualizar diretamente um APK quando o certificado muda.

Na primeira migração para uma release assinada:

1. exporte a biblioteca/sync;
2. desinstale a build debug se o Android recusar a instalação;
3. instale a release assinada;
4. importe o backup.

Depois dessa migração, futuras releases assinadas podem atualizar a instalação existente normalmente.

## Privacidade e segurança

- dados locais em SQLite;
- sem telemetria obrigatória;
- sem anúncios;
- sem venda de dados;
- segredos não são hardcoded;
- APK Android release usa assinatura persistente;
- updater Android verifica SHA-256 antes da instalação;
- execução manual no Desktop usa argumentos tokenizados;
- manifests corrompidos são ignorados/reportados em vez de derrubar scans completos.

## Build

### Desktop Windows

Pré-requisitos:

- Node.js 22+;
- Rust stable;
- dependências do Tauri 2 para Windows.

```bash
npm install
npm run build
cargo fmt --manifest-path src-tauri/Cargo.toml -- --check
cargo check --manifest-path src-tauri/Cargo.toml
cargo test --manifest-path src-tauri/Cargo.toml
npm run tauri -- build --bundles nsis
```

O instalador é gerado em:

```text
src-tauri/target/release/bundle/nsis/
```

### Android debug

Pré-requisitos:

- JDK 17;
- Android SDK.

```bash
gradle -p android :app:assembleDebug
```

APK:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

### Android release

Com a assinatura configurada:

```bash
gradle -p android :app:assembleRelease
```

O pipeline oficial utiliza `android-release.yml`.

## CI

GitHub Actions valida:

- frontend;
- Rust;
- process harness;
- pacote Windows;
- Android debug;
- Android release assinado.

Artifacts e releases são produzidos apenas quando os jobs correspondentes passam.

## Estrutura

```text
src/                                      UI Desktop
src-tauri/src/db.rs                       schema base e biblioteca
src-tauri/src/product.rs                  domínio/migrations/stats/sync
src-tauri/src/providers/                  providers Windows
src-tauri/src/process_monitor.rs          descoberta/classificação de processos
src-tauri/src/sessions.rs                 lifecycle de sessões
src-tauri/src/emulation/                  adapters de emulação
src-tauri/src/metadata.rs                 metadata providers
android/                                  aplicativo Android
android/app/src/main/java/com/ludex/mobile/
                                          código nativo Android
android/SIGNING.md                        assinatura e releases Android
docs/                                     documentação técnica
```

## Limitações conhecidas

- CI não possui contas reais de todos os launchers comerciais;
- alguns launchers/anti-cheats dificultam associação confiável de processos;
- apps MSIX podem ocultar executáveis;
- PlayStation/Xbox não oferecem uma API pública geral que permita importar arbitrariamente toda a biblioteca de usuário;
- histórico de jogo Android disponibilizado a terceiros é incompleto;
- Shizuku via ADB não concede acesso irrestrito às pastas privadas de outros apps;
- artwork GameNative automático está atualmente focado em jogos Steam com App ID conhecido.

## Releases recentes

### Android 0.9.13

- importação de shortcuts do GameNative;
- launch direto por Intent;
- artwork real da Steam para jogos GameNative;
- cache local de imagens;
- updater assinado e fluxo de release automatizado.

### Desktop 0.9.5

- base atual do aplicativo Windows e integração com as melhorias recentes de biblioteca, tracking, providers e Steam.

## Documentação

Consulte também:

- `docs/ARCHITECTURE.md`
- `docs/PROVIDERS.md`
- `docs/EMULATION.md`
- `docs/SYNC.md`
- `docs/PROCESS_TRACKING.md`
- `android/SIGNING.md`
