# Providers

Todos os providers produzem `ScannedInstallation`; persistência, deduplicação, launch tracking e sessões permanecem no core.

## Steam

Detecta roots via Registry/caminhos conhecidos, lê `libraryfolders.vdf` e `appmanifest_*.acf`, mantém AppID como external ID, suporta múltiplas libraries e marca instalações ausentes como desinstaladas em reimportações. Launch usa URI Steam oficial. Artwork pode ser lido do cache local da Steam.

## Epic Games Store

Usa manifests `.item` locais do Epic Launcher para identificador, display name, install location, executable/args quando disponíveis. Não depende de scraping de páginas web.

## GOG

Usa dados locais `goggame-*.info`/instalações detectáveis e favorece executáveis DRM-free diretos quando disponíveis.

## Xbox / Microsoft Store / Game Pass PC

Usa os dados de package/AUMID expostos pelo Windows e launch via `shell:AppsFolder`. Executáveis de pacotes protegidos podem permanecer ocultos; nesses casos o AUMID é a identidade de launch e o tracking depende dos processos que o Windows deixa observar.

## EA App, Ubisoft Connect e Battle.net

Usam Registry/configurações/instalações locais quando há informação sustentável. Não existe tabela hardcoded de todos os jogos. Se o launcher não expuser um target de launch confiável, a instalação permanece importável e pode receber configuração manual.

## Manual

Permite jogo sem launcher conhecido com executable, working directory, argumentos, metadata e plataforma. O mesmo modelo permite títulos antigos/portáteis.

## Extensão

Um novo provider deve:

1. descobrir dados por fonte legítima/local;
2. gerar ID externo estável quando possível;
3. nunca registrar playtime diretamente;
4. fornecer launch target sem concatenar input não confiável em shell;
5. declarar limitações quando discovery/launch não for confiável;
6. adicionar fixtures/testes de parsing antes de habilitar import automático.
