# Sync protocol

O sync da 0.9 usa um envelope JSON versionado e transporte explícito por arquivo. Isso mantém o produto local-first e funciona entre Desktop e Android sem conta ou servidor proprietário.

## Entidades

O envelope transporta, quando presentes: `games`, `installations`, `sessions`, `metadata`, `collections`, `collection_games` e `imported_playtime`.

IDs são estáveis. `play_sessions.id` funciona como chave de idempotência, portanto importar o mesmo arquivo novamente não cria sessões duplicadas.

## Conflitos

- registros inexistentes são inseridos;
- registros com `updated_at` mais recente substituem apenas campos sincronizáveis;
- metadata manual local tem prioridade apropriada e não é apagada silenciosamente por cache automático;
- sessões são imutáveis após encerradas, salvo recovery local controlado;
- vínculos de coleção usam chave composta e são idempotentes.

O import retorna um `SyncSummary` para diagnóstico em vez de ocultar alterações.

## Desktop

Export/import é exposto por commands Tauri. O banco continua plenamente utilizável sem nenhum arquivo de sync presente.

## Android

O app usa o Storage Access Framework para escolher destino/origem do JSON. Usage Stats importadas localmente geram IDs determinísticos por pacote/período para que exportações repetidas não dupliquem uso.

## Segurança

A 0.9 não abre porta LAN automaticamente. O usuário escolhe explicitamente o arquivo a exportar/importar. Isso evita descoberta de rede, credenciais e superfície HTTP desnecessária na primeira release. Um transporte LAN futuro pode reutilizar exatamente o mesmo envelope e regras de conflito.
