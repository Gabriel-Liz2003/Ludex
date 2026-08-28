# Steam artwork migration in 0.9.2

Existing metadata rows whose source is the old `steam-cdn` fallback are migrated lazily during the next Steam sync to virtual `steam-artwork:<appid>` references. Manual metadata remains authoritative and is not overwritten.

This lets existing 0.9.1 databases benefit from the new resolver without deleting/reimporting games.
