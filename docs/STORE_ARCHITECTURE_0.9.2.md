# Arquitetura da Loja 0.9.2

O módulo `store.rs` é independente do provider de instalações. Ele usa Steam AppID como chave de consulta quando aplicável e nunca cria Installation ou PlaySession ao apenas navegar por ofertas. A interface chama `store_catalog` para descoberta inicial e `store_compare` sob demanda, evitando consultar todas as APIs de preço para dezenas de jogos simultaneamente.
