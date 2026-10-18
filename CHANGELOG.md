# Changelog

## Unreleased

- Add focus mode (`/gc focus`) that routes normal chat to a group; a message for a lost focus group is blocked, never leaked to public chat.
- Add per-group mute (`/gc mute`), stored with personal preferences.
- Group messages are clickable to reply; mentioning a member's name plays a ping for them.
- Show group tags in the tab list for fellow members only, using a small packet accessor mixin.
- New permissions `groupchat.command.focus` and `groupchat.command.mute`. Existing data loads unchanged.

## beta2-26.3

- Update Minecraft and Fabric API compatibility to 26.3.
- Carry forward the player-name completion fix from the 26.2 maintenance branch.

## beta2-26.2

- Prevent player-name Tab completion from throwing when Brigadier supplies a command context without a `group` argument.
- Keep suggestions for valid group command paths and add a regression check for the missing-argument context.

## 1.2.1+26.2

- Allow `chat_log_retention_hours = -1` to disable moderation logging; use it as the default for new or missing settings.
- Skip log creation, writes and cleanup while disabled, preserving normal chat delivery and any existing logs.
- Keep existing positive retention settings. Reject `0` with an error explaining the `-1` option.

## 1.2.0+26.2

- Add optional LuckPerms support through the bundled Fabric Permissions API 0.7.0.
- Add individual permission nodes for all player actions and four separate administrative actions. Preserve the existing defaults when permissions are unset, and respect explicit denials even for operators.
- Add the `groupchat.max-owned-groups` meta override for players and inherited ranks, including offline transfer recipients. Retain the TOML limit as the default.
- Load offline permissions without blocking the server thread; reject failed or timed-out lookups. Recheck permissions, group roles and confirmation state before applying the action.
- Fix a null-player exception when the console requested completion for `/gc `.
- Restrict player-name suggestions to the corresponding group role and administrative action. A grant of `groupchat.admin.list` alone cannot reveal a group's membership through ordinary transfer suggestions.
- Add permission, metadata and asynchronous lookup regression coverage. Keep all player-facing text and code comments in English.

See [PERMISSIONS.md](PERMISSIONS.md) for setup and [TESTS.md](TESTS.md) for verification and remaining manual checks. Existing configuration and world data remain compatible.
