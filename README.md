# Ludex

Ludex é uma biblioteca universal de jogos **local-first**, criada para reunir PC, Android, consoles e emulação em uma interface leve inspirada na praticidade de launchers modernos.

> Estado atual: **alpha / fundação funcional**. O repositório começou vazio e esta etapa estabelece arquitetura, banco local, UI e contratos extensíveis antes das integrações específicas.

## Stack

- **Tauri 2 + Rust** — desktop e base compartilhável com Android, sem empacotar Electron/Chromium.
- **TypeScript + Vite** — UI rápida e simples.
- **SQLite (rusqlite)** — biblioteca, instalações, sessões, coleções, ROMs e configurações locais.

## O que já funciona

- janela desktop do Ludex;
- banco SQLite criado automaticamente na pasta de dados do aplicativo;
- cadastro manual de jogos;
- listagem e busca instantânea da biblioteca;
- total básico de jogos/horas armazenadas;
- schema preparado para instalações, sessões, coleções, ROMs e emuladores;
- arquitetura `LibraryProvider` para lojas/plataformas;
- arquitetura `EmulatorAdapter` para emuladores;
- primeira detecção não invasiva da existência da Steam no Windows.

## Desenvolvimento

### Pré-requisitos

- Node.js 22+
- Rust stable
- dependências do Tauri 2 para seu sistema operacional

### Executar UI

```bash
npm install
npm run dev
```

### Executar aplicativo desktop

```bash
npm install
npm run tauri dev
```

### Verificar build

```bash
npm run build
cargo check --manifest-path src-tauri/Cargo.toml
```

## Organização

```text
src/                    UI
src-tauri/src/db.rs     SQLite
src-tauri/src/models.rs domínio
src-tauri/src/providers integrações de bibliotecas
src-tauri/src/emulation abstração de emuladores
docs/ARCHITECTURE.md    decisões arquiteturais
docs/ROADMAP.md         implementação incremental
```

## Regras do projeto

Ludex não distribui ROMs, BIOS protegidas ou conteúdo pirata. Integrações com PlayStation, Xbox e outras plataformas devem usar meios oficiais/legítimos e declarar limitações reais. Funcionalidades básicas não devem depender de conta, telemetria ou servidor proprietário.

## Próximo marco

Transformar o provider Steam em importação real de `libraryfolders.vdf` + `appmanifest_*.acf`, implementar deduplicação e iniciar o monitor de processos do Windows.
