# Credenciais externas

O Ludex 0.9.2 pode usar chaves fornecidas pelo usuário para Steam Web API, IsThereAnyDeal e GG.deals.

No Desktop Windows, essas chaves são criptografadas com DPAPI no escopo `CurrentUser` antes de serem gravadas na tabela `settings`. O frontend recebe apenas o estado `configured`; a chave salva não é devolvida à interface.

Limitações: a proteção está vinculada ao usuário do Windows que salvou a credencial. Copiar apenas o valor cifrado para outra conta/máquina não deve torná-lo utilizável. Backups/exportações futuros que incluam settings devem tratar valores `secret.*` como credenciais e não convertê-los em texto claro.
