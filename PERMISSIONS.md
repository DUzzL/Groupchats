# LuckPerms and permissions

GroupChat 1.2.0 uses Fabric Permissions API 0.7.0, bundled inside the mod JAR. Install the Fabric edition of LuckPerms on the server to manage permissions and ownership limits. No client mod is required. LuckPerms is optional: without a permissions provider, normal player commands remain available, administrative commands require OP level 4 or the console, and the TOML ownership limit applies.

## Command permissions

All player permissions below default to **true** when the provider leaves them unset. Explicit `false` denies the command, including for an operator. Permissions apply equally to `/gc`, `/groupchat`, personal chat aliases and clickable invitation actions.

| Permission | Command |
| --- | --- |
| `groupchat.command.help` | `/gc`, `/groupchat`, `/gc help` |
| `groupchat.command.list` | `/gc list` |
| `groupchat.command.info` | `/gc info <group>` |
| `groupchat.command.create` | `/gc create <name>` |
| `groupchat.command.rename` | `/gc rename <group> <newName>` |
| `groupchat.command.invite` | `/gc invite <group> <player>` |
| `groupchat.command.accept` | `/gc accept <group>` |
| `groupchat.command.decline` | `/gc decline <group>` |
| `groupchat.command.leave` | `/gc leave <group>` |
| `groupchat.command.kick` | `/gc kick <group> <player>` |
| `groupchat.command.coowner.add` | `/gc coowner add <group> <player>` |
| `groupchat.command.coowner.remove` | `/gc coowner remove <group> <player>` |
| `groupchat.command.delete` | `/gc delete <group> [confirm]` |
| `groupchat.command.transfer` | `/gc transfer <group> <player> [confirm]` |
| `groupchat.command.shorten` | `/gc shorten <group> [alias]` |
| `groupchat.command.color` | `/gc color <group> <color>` |
| `groupchat.command.chat` | `/gc <group-or-alias> <message>` |

The four administrative permissions default to **OP level 4 / console** when unset. Grant them individually to allow non-OP administrators to perform specific actions.

| Permission | Command |
| --- | --- |
| `groupchat.admin.list` | `/gc admin list` |
| `groupchat.admin.info` | `/gc admin info <group>` |
| `groupchat.admin.delete` | `/gc admin delete <group> [confirm]` |
| `groupchat.admin.transfer` | `/gc admin transfer <group> <player> [confirm]` |

The `/gc admin` help entry is available when at least one administrative permission is allowed. It does not require a separate permission. Confirmation commands reuse their action's permission and check it again when executed. Sending a chat message requires `groupchat.command.chat`; receiving messages remains based on group membership.

**Group roles still apply.** Granting `groupchat.command.rename` does not let an ordinary member rename the group. Only its owner or co-owners can do that. Ordinary transfer and deletion still require ownership. Administrative permissions allow their named administrative action but do not make the administrator a member or deliver private messages to them. Transfers still require an existing member and an available ownership slot.

Examples (omit the initial slash in the server console):

```text
/lp group default permission set groupchat.command.create false
/lp group vip permission set groupchat.command.create true
/lp group moderator permission set groupchat.admin.list true
/lp group moderator permission set groupchat.admin.info true
/lp group administrator permission set groupchat.admin.* true
/lp user Alex permission set groupchat.command.chat false
```

LuckPerms handles inheritance, contexts and wildcard nodes such as `groupchat.command.*`, `groupchat.command.coowner.*` and `groupchat.admin.*`. Unsetting a node restores the inherited result or the default above; use `false` when an explicit denial is intended.

## Ownership limits through metadata

Meta key: **`groupchat.max-owned-groups`**.

```text
/lp group vip meta set groupchat.max-owned-groups 5
/lp user Alex meta set groupchat.max-owned-groups 8
/lp user Alex meta unset groupchat.max-owned-groups
```

This is a **meta value**, not a permission node. It accepts a whole number from `0` to `100000`. Zero prevents the player from creating or receiving ownership of another group. There is no special unlimited value. If the key is missing, the mod uses `max_owned_groups` from `config/groupchats/groupchat.toml`. Invalid, negative or excessively large values also use the TOML value and produce a server warning.

The effective value can come from the player or an inherited LuckPerms rank. It is checked when creating a group and for the recipient of both normal and administrative ownership transfers, including again at confirmation. Lowering a limit does not delete existing groups or remove memberships/co-owner roles.

Online players use their current permission context. Offline recipients use the provider's offline/static context. Set this quota globally or in an appropriate server context; a world-only context may not apply while the recipient is offline. Offline permissions load asynchronously. A pending lookup is announced in chat, times out after 10 seconds if necessary, and never falls back to a permissive value after a lookup failure. A disconnected requester cannot complete a pending action. Roles and command permissions are checked again before applying it.

LuckPerms changes take effect on subsequent commands without restarting GroupChat. Changes to the TOML file still require a server restart.

References: [Fabric Permissions API usage](https://github.com/lucko/fabric-permissions-api/blob/master/USAGE.md), [LuckPerms meta commands](https://luckperms.net/wiki/Meta-Commands), [LuckPerms contexts](https://luckperms.net/wiki/Context).
