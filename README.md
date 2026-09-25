# Ludex

Ludex é uma biblioteca e launcher universal de jogos **local-first** para Windows e Android. O projeto reúne jogos instalados em diferentes launchers, títulos importados, ROMs, sessões e playtime em uma única biblioteca, sem exigir um servidor proprietário para funcionar.

> O projeto ainda está na linha **0.9.x** e deve ser tratado como beta. A base já é funcional, mas ainda existem integrações experimentais, limitações de sync e alguns pontos de CI/release que precisam de endurecimento antes de uma 1.0.

## Estado atual

| Plataforma | Versão publicada | Stack | Situação |
| --- | ---: | --- | --- |
| Windows Desktop | **0.9.5** | Tauri 2 + Rust + TypeScript/Vite + SQLite | Funcional |
| Android | **0.9.21** | Java + AndroidX/Material + SQLite | Desenvolvimento mais ativo |

O `main` atualmente contém o Android **0.9.21**. Há trabalho posterior em branches/PRs que pode ainda não fazer parte da versão publicada.

## O que já existe

- biblioteca unificada de jogos;
- importação de múltiplos providers no Windows;
- jogos manuais;
- deduplicação conservadora e correções manuais por merge/split;
- tracking próprio de sessões no Desktop;
- importação de playtime histórico quando uma fonte confiável está disponível;
- coleções, favoritos, status, recentes e estatísticas;
- emulação e biblioteca de ROMs;
- backup de saves no Desktop;
- comparação de preços via Steam, IsThereAnyDeal e GG.deals;
- sync por JSON entre Desktop e Android para o subconjunto de dados compatível;
- funcionamento local/offline para a biblioteca já importada;
- biblioteca Android nativa;
- integração Android com GameNative, Shizuku e Eden;
- importação de atividade da Steam e Nintendo no Android;
- artwork local/remoto com cache;
- APK Android assinado e updater com verificação SHA-256.

---

# Windows Desktop

## Arquitetura

O Desktop usa:

- **Tauri 2** para o shell do aplicativo;
- **Rust** no backend;
- **TypeScript + Vite** no frontend;
- **SQLite** como fonte de verdade local;
- migrations aditivas para preservar bancos anteriores.

A modelagem separa principalmente:

- `games`: identidade global do jogo;
- `installations`: instalações jogáveis por provider/dispositivo;
- `external_ids`: IDs estáveis externos;
- `play_sessions`: sessões medidas pelo Ludex;
- `imported_playtime`: histórico importado de outras plataformas;
- `game_metadata`: metadata e artwork;
- `collections`: organização criada pelo usuário;
- `emulators` / `roms`: emulação;
- tabelas auxiliares para tracking de processos, backups e sync.

## Biblioteca

A interface Desktop já possui:

- grid e lista;
- busca;
- filtros;
- favoritos;
- recentes;
- instalados/não instalados;
- status de backlog;
- detalhes do jogo;
- múltiplas instalações para uma mesma identidade;
- seleção de instalação ao iniciar;
- cadastro manual com executável, working directory e argumentos;
- coleções;
- edição manual de metadata;
- merge/split para corrigir associações;
- estatísticas;
- diagnóstico;
- área de emulação;
- área de loja/comparação de preços.

## Providers

Todos os providers convertem seus resultados para o mesmo modelo de instalação. O tracking de sessão permanece separado do provider.

| Provider | Descoberta/importação | Launch | Observação |
| --- | --- | --- | --- |
| Steam | Registry, `libraryfolders.vdf`, `appmanifest_*.acf` | `steam://rungameid` | Integração mais completa |
| Epic Games Store | manifests locais `.item` | URI da Epic | Usa dados locais do launcher |
| GOG | `goggame-*.info` | executável DRM-free quando disponível | Usa `playTask` primário |
| Xbox / Microsoft Store | AUMIDs visíveis pelo Windows | `shell:AppsFolder` | Detecção é conservadora e pode ser incompleta |
| EA App | pastas locais conhecidas | executável detectado | Heurística; alguns jogos podem exigir ajuste manual |
| Ubisoft Connect | Registry | `uplay://` | Usa o ID registrado pelo launcher |
| Battle.net | instalações com `.build.info` | não automático | Product code não é inferido sem fonte confiável |
| Manual | configurado pelo usuário | executável direto | Útil para portáteis e casos não cobertos |

### Steam

Além das instalações locais, o Ludex consegue:

- detectar a conta Steam usada localmente;
- importar playtime do `localconfig.vdf`;
- reutilizar artwork local do cache da Steam;
- importar opcionalmente a biblioteca da conta via `IPlayerService/GetOwnedGames`;
- manter playtime histórico separado do tempo medido pelo Ludex;
- proteger a Steam Web API key com **DPAPI / CurrentUser** no Windows.

## Tracking de sessões

O tracking do Desktop é independente da loja.

O fluxo usa:

- snapshot de processos;
- PID, PPID, executable e start time;
- árvore de processos;
- classificação entre jogo, launcher, anti-cheat e processos ignorados;
- scoring de candidatos;
- thresholds diferentes para jogos iniciados pelo Ludex e sessões externas;
- heartbeat;
- acompanhamento de filhos/descendentes;
- recuperação de sessão após crash;
- proteção contra PID reuse;
- confirmação antes de encerrar quando somente launchers auxiliares permanecem.

O projeto possui testes unitários e um harness Windows que cria uma árvore falsa com launcher, jogo, anti-cheat e launcher persistente.

## Loja e preços

A área **Loja** usa:

- Steam para catálogo/preço;
- IsThereAnyDeal para ofertas de lojas oficiais, com API key opcional;
- GG.deals para menor preço agregado em lojas oficiais e keyshops, com API key opcional.

As chaves são protegidas com DPAPI e não são devolvidas ao frontend depois de salvas.

O Ludex não processa pagamentos e não armazena credenciais de cartão ou login de lojas.

## Emulação no Desktop

O Desktop possui configuração genérica de emuladores e presets para:

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

O scanner suporta, entre outros:

`iso`, `chd`, `cue`, `bin`, `rvz`, `wbfs`, `gba`, `gbc`, `nds`, `3ds`, `nsp`, `xci`, `nes`, `snes`, `n64`, `z64`, `v64` e `pbp`.

ROMs recebem hash SHA-256 e podem usar o mesmo pipeline de sessão dos jogos PC.

Quando um emulador possui `saves_directory`, o Ludex também permite criar backups manuais de saves. O restore evita sobrescrever silenciosamente um destino já existente.

---

# Android

O app Android em `android/` é **nativo**, usando Java + AndroidX/Material. Ele não é uma WebView do frontend Desktop.

## Biblioteca Android

O Ludex consegue:

- detectar aplicativos marcados como jogos pelo Android;
- abrir jogos diretamente;
- ler `UsageStatsManager` após autorização do usuário;
- importar tempo em foreground disponível pelo sistema;
- calibrar manualmente um baseline quando o histórico do Android está incompleto;
- pesquisar a biblioteca;
- separar jogos Android e emulados;
- favoritar;
- alterar status;
- remover/ocultar jogos da biblioteca com undo;
- exportar/importar o bundle JSON compatível.

O Android não oferece uma API pública geral que permita a terceiros recuperar todo o histórico oficial exibido por Play Store/Play Games. Por isso o Ludex usa Usage Access e trata esse histórico como uma aproximação do que o sistema disponibiliza.

## GameNative

A integração com **GameNative** suporta:

- descoberta de shortcuts publicados pelo launcher;
- consulta de shortcuts com Shizuku;
- fallback por armazenamento quando possível;
- reconhecimento de provider e external ID;
- launch direto com o Intent `app.gamenative.LAUNCH_GAME`;
- importação de playtime Steam para jogos GameNative/Steam;
- artwork da Steam para App IDs conhecidos;
- cache local de artwork.

### Limitação do Shizuku

Shizuku iniciado via ADB normalmente executa como usuário `shell` e não concede acesso arbitrário a `/data/user/0` de outros aplicativos. Por isso o Ludex prefere shortcuts e dados públicos em vez de depender da pasta privada do GameNative.

A implementação também utiliza APIs de processo do Shizuku por reflexão em alguns scanners; essa integração deve ser considerada sensível a mudanças futuras do Shizuku/Android.

## Emuladores no Android

O app mantém uma área própria para emuladores instalados e consegue conectar bibliotecas por pasta usando o Storage Access Framework.

Para emuladores genéricos, o Ludex:

- importa ROMs da pasta autorizada;
- cria cards individuais;
- abre o jogo pelo emulador;
- registra uma sessão aproximada entre o launch e o retorno ao Ludex.

Esse tracking genérico é **heurístico**. Se o processo for encerrado, o usuário permanecer fora do Ludex por muito tempo ou o Android destruir a Activity, a duração pode não representar com precisão o tempo efetivo dentro do jogo.

## Eden

A integração com Eden possui tratamento específico para Nintendo Switch:

- importação da biblioteca por pasta de ROMs;
- suporte a `.xci`, `.nsp`, `.nca` e `.nro`;
- extração de Program ID quando presente no nome;
- recuperação do Program ID usado recentemente via logcat quando disponível;
- leitura do `playtime.bin` via Shizuku;
- associação do playtime do Eden ao jogo correto;
- launch direto da ROM;
- artwork de jogos emulados.

## Conta Nintendo

O Android também possui sincronização opcional de **Play Activity** da Conta Nintendo.

O fluxo:

1. abre o login no domínio oficial `accounts.nintendo.com`;
2. usa PKCE e valida `state`;
3. o Ludex não recebe a senha digitada;
4. o session token é armazenado criptografado com **Android Keystore + AES/GCM**;
5. o app consulta o histórico de atividade e importa horas/capas.

**Importante:** a consulta de Play Activity usa uma API **não documentada/publicamente suportada** usada pelo ecossistema de apps da Nintendo. Ela pode mudar ou deixar de funcionar sem aviso e deve ser tratada como integração experimental.

No `main` 0.9.21, Nintendo e Eden ainda podem produzir identidades separadas em alguns cenários. Existe trabalho em andamento para associar o playtime da Nintendo diretamente aos mesmos cards da biblioteca emulada quando houver ID/título confiável.

## Artwork de emulados

O Android usa:

- cache local;
- Libretro thumbnails para sistemas compatíveis;
- SteamGridDB como fallback opcional mediante API key;
- artwork Nintendo quando importado pela integração de Play Activity;
- artwork Steam para GameNative/Steam.

Secrets Android são protegidos com Android Keystore.

---

# Sync, backup e offline

O Ludex não exige conta própria.

Existem dois envelopes JSON compatíveis na versão 1:

- `ludex-backup` no Desktop;
- `ludex-mobile-sync` no Android.

O subconjunto realmente compartilhado entre Desktop e Android hoje é principalmente:

- jogos;
- sessões;
- playtime importado.

O Desktop também exporta dados adicionais, como instalações, metadata, coleções, achievements, emuladores e ROMs. Nem todas essas entidades têm import/restore equivalente nas duas plataformas ainda.

No Android, itens específicos do dispositivo — como settings, associações de emulador/GameNative/Eden, estado de jogos ocultos e secrets — permanecem locais e não fazem parte do bundle móvel atual.

Por isso o sync da linha 0.9 deve ser entendido como **interoperabilidade parcial**, não como replicação integral de estado entre dispositivos.

---

# Atualizações

## Android

O updater Android procura Releases com tags:

```text
android-v<versão>
```

Ele:

1. encontra uma versão mais nova;
2. baixa APK e arquivo SHA-256;
3. valida o hash localmente;
4. abre o instalador do Android.

O workflow `.github/workflows/android-release.yml` gera APK assinado, valida a assinatura com `apksigner` e publica os assets.

Secrets necessários:

```text
ANDROID_KEYSTORE_BASE64
ANDROID_STORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

Mais detalhes em `android/SIGNING.md`.

## Desktop

Existe um updater Desktop em Rust e o workflow `.github/workflows/release.yml` publica instaladores NSIS nas tags `v<versão>`.

### Limitação conhecida do updater Desktop 0.9.5

A implementação atual consulta o endpoint de **latest release** do repositório. Como o mesmo repositório também publica releases Android com tags `android-v...`, uma release Android pode se tornar a latest e não é uma versão SemVer válida para o parser do updater Desktop.

Enquanto a seleção de releases não for separada por plataforma, o update in-app do Desktop deve ser considerado **não confiável**. Releases Windows `vX.Y.Z` continuam disponíveis no GitHub.

---

# CI e releases

O workflow `.github/workflows/check.yml` valida:

- build do frontend;
- `cargo fmt --check`;
- `cargo check`;
- `cargo test`;
- harness real de processos no Windows;
- APK Android debug;
- pacote Windows NSIS.

Os workflows de release Desktop e Android são separados do workflow de check.

**Limitação atual:** uma publicação pode ser disparada mesmo que outro job do workflow de validação falhe. Antes de uma 1.0, os releases devem ser explicitamente bloqueados até a suíte obrigatória ficar verde.

Também existem workflows/scripts antigos de migração/reparo mantidos por histórico; eles não representam a pipeline principal e podem ser removidos quando não forem mais necessários.

---

# Privacidade e segurança

- biblioteca e sessões ficam em SQLite local;
- sem telemetria obrigatória;
- sem anúncios;
- sem venda de dados;
- secrets Desktop protegidos com DPAPI;
- secrets Android protegidos com Android Keystore/AES-GCM;
- APK release assinado;
- updater Android valida SHA-256;
- argumentos de executáveis Desktop são tokenizados em vez de concatenados em shell;
- URLs externas abertas pelo Desktop usam allowlist;
- manifests inválidos são ignorados/reportados em vez de derrubar o scan inteiro;
- restore de saves evita sobrescrita silenciosa.

### Pontos de hardening ainda pendentes

- o CSP do Tauri está atualmente desabilitado;
- o updater Desktop precisa separar releases Windows/Android e endurecer a política de integridade;
- releases precisam ser gated pela suíte de CI;
- integrações não oficiais, como Nintendo Play Activity, precisam continuar isoladas e documentadas como experimentais.

---

# Build

## Desktop Windows

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

Instalador:

```text
src-tauri/target/release/bundle/nsis/
```

## Android debug

Pré-requisitos:

- JDK 17;
- Android SDK 36.

```bash
gradle -p android :app:assembleDebug
```

APK:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## Android release

Com assinatura configurada:

```bash
gradle -p android :app:assembleRelease
```

O pipeline oficial está em `.github/workflows/android-release.yml`.

---

# Estrutura do repositório

```text
src/
  main.ts                         UI Desktop
  styles.css                     estilos Desktop

src-tauri/
  src/lib.rs                     commands Tauri e integração do app
  src/db.rs                      schema base/importação/deduplicação
  src/product.rs                 domínio, migrations, stats, sync e backups
  src/process_monitor.rs         descoberta/classificação de processos
  src/sessions.rs                lifecycle das sessões
  src/providers/                 providers Windows
  src/emulation/                 emulação Desktop
  src/metadata.rs                metadata/artwork
  src/steam_account.rs           biblioteca da conta Steam
  src/steam_data.rs              playtime/artwork local Steam
  src/store.rs                   catálogo e comparação de preços
  src/secrets.rs                 secrets protegidos no Windows
  src/updater.rs                 updater Desktop

android/
  app/src/main/java/com/ludex/mobile/
                                  app Android nativo
  SIGNING.md                     assinatura/release Android

docs/                             documentação técnica
.github/workflows/                CI e releases
scripts/                          helpers/migrações/harness
```

---

# Limitações conhecidas

- o projeto está em beta 0.9.x;
- providers dependem do que cada launcher expõe localmente;
- EA, Xbox/MS Store e Battle.net possuem descoberta mais limitada que Steam;
- launch Battle.net automático não é habilitado sem um product code confiável;
- launchers/anti-cheats podem exigir regras específicas de associação de processo;
- Android UsageStats não equivale ao histórico oficial completo da conta;
- tracking de emuladores genéricos no Android é aproximado;
- sync Desktop ↔ Android ainda não replica todas as entidades;
- Nintendo Play Activity depende de API não documentada;
- Shizuku não concede acesso irrestrito às pastas privadas de outros apps;
- o updater Desktop 0.9.5 conflita com o modelo atual de releases Android;
- a pipeline de release ainda não é gated pelo resultado completo de CI;
- frontend Desktop e `MainActivity.java` concentram muita responsabilidade e precisam ser modularizados à medida que o produto crescer.

---

# Releases recentes

## Android 0.9.21

- melhorias no mapeamento de playtime do Eden;
- persistência de Program IDs detectados;
- recuperação de Program ID a partir do logcat após launch;
- base atual das integrações de biblioteca emulada, Conta Nintendo e GameNative.

## Android 0.9.20

- remover/ocultar jogo da biblioteca;
- persistência do estado oculto;
- undo na interface.

## Android 0.9.19

- conexão com Conta Nintendo;
- importação de Play Activity/playtime;
- artwork e persistência do histórico Nintendo.

## Android 0.9.18 e anteriores recentes

- biblioteca emulada separada;
- Eden;
- GameNative;
- Shizuku;
- sync de playtime Steam;
- artwork e cache;
- updater Android assinado.

## Desktop 0.9.5

- biblioteca universal Windows;
- providers;
- tracking de sessões;
- conta Steam;
- store/comparação de preços;
- emulação;
- sync/backup;
- melhorias de performance para bibliotecas maiores.

---

# Documentação técnica

Consulte também:

- `docs/ARCHITECTURE.md`
- `docs/PROVIDERS.md`
- `docs/EMULATION.md`
- `docs/SYNC.md`
- `docs/PROCESS_TRACKING.md`
- `docs/STORE.md`
- `docs/STEAM_ACCOUNT.md`
- `docs/SECURITY_KEYS.md`
- `android/SIGNING.md`

> `docs/ROADMAP.md` ainda contém itens históricos que já foram implementados e precisa ser revisado em uma próxima limpeza de documentação.
