# Emulação

## EmulatorAdapter

Cada emulador é configurado com executable, plataforma, template de argumentos, ROM directory e, opcionalmente, BIOS/saves. O adapter monta `Command` + argumentos sem depender de shell concatenado.

Presets existentes cobrem RetroArch, Dolphin, PCSX2, RPCS3, PPSSPP, DuckStation, Cemu, Ryujinx/alternativa configurável, melonDS e mGBA. Presets são atalhos; qualquer executável compatível pode ser cadastrado manualmente.

## Scanner de ROMs

O scanner é recursivo, ignora arquivos fora da allowlist, calcula SHA-256 e usa path/hash para evitar duplicatas. Extensões comuns incluem ISO/CHD/CUE/BIN/RVZ/WBFS/GBA/GBC/NDS/3DS/NSP/XCI/NES/SNES/N64/Z64/V64/PBP.

Extensão ambígua não define plataforma sozinha. O scan recebe plataforma/emulador configurados pelo usuário quando necessário.

## Launch e tracking

Uma ROM persistida é transformada em instalação `emulation`. JOGAR resolve o emulador, renderiza argumentos/core/profile configurados e inicia o processo. Depois disso o fluxo é o mesmo de um jogo PC: ProcessMonitor → PlaySession → heartbeat → estatísticas.

## Saves

Se `saves_directory` estiver configurado, a UI permite backup manual. O backup copia arquivo/árvore para a área local de backups e registra origem, destino, emulador e timestamp. Restore recusa sobrescrever um destino já existente, evitando sincronização/restauração destrutiva.

Ludex não distribui ROMs, BIOS ou firmware proprietário.
