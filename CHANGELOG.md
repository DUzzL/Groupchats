# Changelog

## 1.2.0+26.2

- Add optional LuckPerms support through the bundled Fabric Permissions API 0.7.0.
- Add individual permission nodes for all player actions and four separate administrative actions. Preserve the existing defaults when permissions are unset, and respect explicit denials even for operators.
- Add the `groupchat.max-owned-groups` meta override for players and inherited ranks, including offline transfer recipients. Retain the TOML limit as the default.
- Load offline permissions without blocking the server thread; reject failed or timed-out lookups. Recheck permissions, group roles and confirmation state before applying the action.
- Fix a null-player exception when the console requested completion for `/gc `.
- Restrict player-name suggestions to the corresponding group role and administrative action. A grant of `groupchat.admin.list` alone cannot reveal a group's membership through ordinary transfer suggestions.
- Add permission, metadata and asynchronous lookup regression coverage. Keep all player-facing text and code comments in English.

See [PERMISSIONS.md](PERMISSIONS.md) for setup and [TESTS.md](TESTS.md) for verification and remaining manual checks. Existing configuration and world data remain compatible.
