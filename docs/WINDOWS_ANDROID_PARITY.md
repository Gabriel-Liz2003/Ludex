# Windows ↔ Android: paridade da biblioteca (incremento Desktop)

Este incremento é um passo de portabilidade **de dados e funções de biblioteca**, não uma emulação do sistema Android no Windows.

## Entregue

- Nova aba **Plataformas** no Windows: Steam, Epic, GOG, PlayStation, Nintendo, Xbox/Game Pass, Riot, Android e demais bibliotecas presentes no banco.
- Filtro de biblioteca que exibe **apenas as horas da plataforma selecionada**. Na vista `Todas`, as horas são combinadas entre plataformas.
- Soma do tempo entre origens diferentes; para a mesma origem, usa o maior entre histórico importado e sessões acompanhadas, evitando contagem dupla de snapshots.
- Horas de cada plataforma exibidas nos detalhes do jogo e em listas, inclusive títulos sem instalação Windows.
- Nova aba **Sincronização**: importar um JSON exportado pelo Android através do seletor de arquivo (sem colar manualmente) e exportar um backup JSON compatível com o aplicativo Android.
- Mantidas favoritas, coleções, estatísticas, sessões, emulação e todas as integrações já presentes na versão Windows.
- Fontes Ren'Py identificadas são ocultadas no filtro padrão. A exportação padrão do Android **já exclui** jogos Ren'Py privados (não transfere esses jogos para o desktop).

## Limites existentes

- A interface Windows é nativa Tauri/Rust; não executa `UsageStatsManager`, Shizuku, Eden ou APIs de leitura de APKs. Os dados desses jogos são importados através do JSON exportado no celular.
- O transporte continua **por arquivo selecionado pelo usuário**; a sincronização não é automática pela rede e não há conta/cloud incluído.
- É preciso exportar do Android e importar no Windows para ver a biblioteca móvel. Instalar o Windows app sozinho não lê a memória do telefone.
- A soma entre bibliotecas pode exigir mesclar manualmente identidades duplicadas da mesma obra; o merge/split existente no Ludex continua disponível.
- Os totais importados são snapshots históricos; adicionar sessões novas da mesma plataforma só aumenta o total quando ultrapassar o snapshot, não equivale a somar 100% de tempo após o import sem um marcador de origem.
- O inventário de recursos Android exclusivos **ainda não representa paridade funcional completa**. Futuras etapas podem incluir configuração compartilhada de imagens, gerenciamento de dispositivos, sync LAN opt-in e atendimento de outros provedores.

## Testes

O teste `library_sources::tests::separates_platforms_without_double_counting_snapshots` confere soma Steam + Riot e evita contar sessões Steam importadas novamente. Os checks do GitHub verificam Rust, frontend, Android, process harness e empacotamento Windows.
