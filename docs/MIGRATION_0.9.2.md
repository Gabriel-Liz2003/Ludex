# Migração de dados 0.9.2

A 0.9.2 não remove dados existentes. Jogos e instalações Steam conservam os IDs já usados. A sincronização de conta adiciona `external_ids`, `installations` não instaladas e `imported_playtime` apenas quando necessário; a sincronização local continua atualizando a mesma instalação quando o jogo existe no disco.

Metadata manual permanece prioritária. Fallbacks antigos com source `steam-cdn` podem ser substituídos por referências virtuais `steam-artwork:*` para que o novo resolver escolha cache local ou metadata da loja dinamicamente.
