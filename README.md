# GroupChat

A Fabric server-only mod for private group conversations in Minecraft Java. Players can organize conversations with friends or teammates while keeping the public chat clear. Players do **not** need a client mod: commands, colors and clickable invitations use standard Minecraft features.

Create a group, invite players and send messages with `/gc` or `/groupchat`. Each member can choose a personal color and short alias. Owners and co-owners manage membership, while server administrators can inspect group details and recover ownership when needed. 

## Features

- Private messages delivered to online group members.
- Clickable invitations for online and known offline players, with configurable cooldown and expiry.
- Owner, co-owner and member roles with separate management rights.
- Personal one- or two-character aliases and all standard Minecraft chat colors.
- Optional LuckPerms integration with individual command permissions and ownership limits per player or rank.
- Optional moderation logs for each group, with automatic retention. Disabled by default.
- Persistent groups, invitations, roles and personal preferences across server restarts.

## Configuration

```toml
invite_cooldown_seconds = 60
max_owned_groups = 3
invite_expiry_days = 7
chat_log_retention_hours = -1
```

- The invitation cooldown applies to each **sender/recipient pair across all groups**. Another sender may independently invite the same recipient. Set it to `0` to disable the cooldown.
- A pending invitation to the same group cannot be sent again. Accepting, declining, changing groups or restarting the server does not bypass the cooldown.
- Only owned groups count toward the ownership limit. Memberships and co-owner roles do not count. Administrative transfers also respect the limit.
- Invitations expire after seven real days by default, including offline time. Changing this setting affects newly issued invitations.
- Moderation logging is **disabled by default** with `chat_log_retention_hours = -1`. Set a whole number from 1 to 87600 to enable logging with that retention period in hours, for example `24` for one day. `0` is invalid; use `-1` to disable the feature. Restart the server after changing this setting.
- Deletion and ownership transfer require a second command with `confirm` within **30 seconds**. These actions have **no confirmation buttons**.
- Invalid configuration values produce a startup error. The invalid file is not overwritten with defaults.

## Commands

`/gc` and `/groupchat` are equivalent. Enter placeholders without angle brackets. Group names and aliases are case-insensitive. Personal aliases are used for sending messages; management commands require the full group name.

| Command | Purpose |
| --- | --- |
| `/gc` or `/gc help` | Show help |
| `/gc list` | List your groups, roles, aliases and pending invitations |
| `/gc info <group>` | Show the owner, co-owners and members |
| `/gc create <name>` | Create a group and become its owner |
| `/gc rename <group> <newName>` | Rename a group |
| `/gc invite <group> <player>` | Invite a known online or offline player |
| `/gc accept <group>` | Accept an invitation |
| `/gc decline <group>` | Decline an invitation |
| `/gc leave <group>` | Leave a group |
| `/gc kick <group> <player>` | Remove a member |
| `/gc coowner add <group> <player>` | Promote a member to co-owner |
| `/gc coowner remove <group> <player>` | Demote a co-owner to a regular member |
| `/gc delete <group>` | Request group deletion |
| `/gc delete <group> confirm` | Confirm deletion within 30 seconds |
| `/gc transfer <group> <player>` | Request an ownership transfer |
| `/gc transfer <group> <player> confirm` | Confirm the transfer within 30 seconds |
| `/gc <group> <message>` | Send a group message |
| `/gc shorten <group> <alias>` | Set a personal alias of 1–2 characters |
| `/gc shorten <group>` | Remove your personal alias |
| `/gc <alias> <message>` | Send a message using your alias |
| `/gc color <group> <color>` | Set your personal group color; Tab shows the choices |

The group owner creates a group and invites Alex:

```text
/gc create Basemates
/gc invite Basemates Alex
```

Alex clicks **Accept** in the invitation or runs:

```text
/gc accept Basemates
```

Any member can then choose their personal alias and color, and send a message:

```text
/gc shorten Basemates b
/gc color Basemates green
/gc b Hello everyone!
```

Invited players can click **[Accept]** or **[Decline]** in the invitation. Offline players receive their valid invitations when they next join; `/gc list` also displays them.

Offline players are known through previous logins with this mod or the server's existing `usercache.json`. Unknown names are not looked up online. Memberships use player UUIDs, so they survive player name changes.

## Roles

| Action | Member | Co-owner | Owner |
| --- | --- | --- | --- |
| Chat, personal color/alias, group information | Yes | Yes | Yes |
| Invite and rename | No | Yes | Yes |
| Kick regular members | No | Yes | Yes |
| Kick other co-owners | No | No | Yes |
| Add or remove co-owners | No | No | Yes |
| Delete or transfer ownership | No | No | Yes |
| Leave | Yes | Yes | Transfer or delete first |

The owner cannot be kicked. Ownership can only be transferred to an **existing member** who has room within their ownership limit. The former owner becomes a **regular member**. The new owner loses any previous co-owner role; other co-owners keep theirs.

Leaving or being kicked removes that member's personal preferences and co-owner role. Pending invitations issued by a departing member or a demoted co-owner are revoked. Changes to the group's name, membership or roles invalidate pending deletion/transfer confirmations. A confirmation belongs to one specific actor, action, group and target; it must be requested again after a restart or disconnect.

## Server administration

Administrative commands default to **OP level 4** or the server console. With LuckPerms, each administrative action can be granted or denied separately. Omit the leading slash in the console.

```text
/gc admin list
/gc admin info <group>
/gc admin delete <group>
/gc admin delete <group> confirm
/gc admin transfer <group> <player>
/gc admin transfer <group> <player> confirm
```

Administrators can inspect all groups and their owners, co-owners and members. They can delete or transfer a group even when its owner is unavailable. The new owner must still be an existing member with an available ownership slot. These commands do not display chat contents; server operators can read the moderation log files. Deletions and transfers are recorded in the server log.

## LuckPerms

Every command has its own permission. Normal player permissions default to true, while administrative permissions default to OP level 4 or the console. Explicit denies are respected, and group roles still apply.

The `groupchat.max-owned-groups` meta value overrides the TOML ownership limit for a player or inherited rank. It also applies when transferring a group to an offline player. Lowering a limit does not delete existing groups.

```text
/lp group vip meta set groupchat.max-owned-groups 5
/lp group moderator permission set groupchat.admin.list true
/lp group moderator permission set groupchat.admin.info true
/lp user Alex permission set groupchat.command.create false
```

See [PERMISSIONS.md](PERMISSIONS.md) for all nodes, defaults, examples and offline context behavior. Without LuckPerms, the original command defaults and TOML limit remain in effect.

## Names, aliases and display

Group names contain **3–20 ASCII characters**: `A–Z`, `a–z`, `0–9`, `_` and `-`. Spaces and accented letters are not allowed. Names are unique across the server regardless of case, while the chosen spelling is preserved for display.

Reserved command words: `gc`, `groupchat`, `create`, `rename`, `delete`, `confirm`, `invite`, `kick`, `transfer`, `accept`, `decline`, `leave`, `shorten`, `color`, `coowner`, `add`, `remove`, `list`, `info`, `help`, `admin`.

Aliases use the same character set, contain 1–2 characters and must be unique for each player. Different players may use the same alias. Renaming a group preserves memberships and personal preferences.

Colors: `black`, `dark_blue`, `dark_green`, `dark_aqua`, `dark_red`, `dark_purple`, `gold`, `gray`, `dark_gray`, `blue`, `green`, `aqua`, `red`, `light_purple`, `yellow`, `white`. The default is `white`.

Example message:

[**Basemates**] [b] Alex: Hello everyone!

Only the group name is bold. The entire line, including brackets, alias and sender, uses the **recipient's chosen color**. The alias block is omitted when that recipient has no alias. Messages are delivered only to currently connected group members. There is no delayed delivery or player-accessible chat history. Server moderation logs are described below.

## Moderation logs

Set `chat_log_retention_hours` to a positive number of hours to enable moderation logs. With `-1` (the default), messages are delivered normally without logging, no log directory is created, and automatic log cleanup is disabled. Existing logs are left untouched while logging is disabled.

```text
config/
└── groupchats/
    ├── groupchat.toml
    └── logs/
        ├── <group-uuid>.jsonl
        └── <another-group-uuid>.jsonl
```

Each group with messages has its own UTF-8 JSON Lines file, with one JSON object per message. Fields are `timestamp` (UTC), `group_id`, `group_name`, `sender_uuid`, `sender_name` and `message`. Each message is recorded once, without personal colors or aliases. Invitations and system notifications are not recorded in these group chat files.

The stable group UUID in the filename keeps the same log associated with a group after a rename. Each entry records the group name at the time of the message. A newly created group reusing an old name receives a new UUID and its own log.

Retention uses a **rolling window for each message**. Expired entries are removed at startup, during normal shutdown and about once a minute while the server runs. Recent entries in the same file remain; empty files are deleted. Cleanup includes inactive and deleted groups, so deleting a group does not immediately erase its moderation logs. Physical removal waits until the next cleanup, normally within about a minute of expiry. Cleanup cannot run while the server is stopped; overdue entries are removed on the next startup.

Logs can be opened in a text editor or processed as JSON Lines. Each message is written before delivery. If writing fails, the sender receives an error and the message is not sent. Logs with unreadable timestamps are preserved and reported for administrator review; other groups are still cleaned up. The mod creates no `.bak` copies of chat logs.

## Upgrading

Stop the server, replace the previous GroupChat JAR with a build compatible with your Minecraft version, then restart. Keep only one GroupChat release JAR in `mods`.

A legacy `config/groupchat.toml` is automatically moved to `config/groupchats/groupchat.toml`, preserving custom values and comments. The log retention setting is added if missing. An existing file at the new path takes precedence; an additional old file is left untouched.

Groups and memberships remain in the existing world data file. See [CHANGELOG.md](CHANGELOG.md) for release-specific changes.

## Persistent data

Groups, memberships, roles, invitations, invitation cooldowns and personal preferences are stored in `<world>/data/groupchat.json`. Changes are saved immediately. The previous snapshot is kept in `groupchat.json.bak`; this does not replace regular world backups.

Saving uses a temporary file and an atomic replacement where supported. Failed saves roll back the in-memory change. Unreadable or structurally invalid data is not silently overwritten. Stop the server before restoring a valid backup.


## License

GroupChat is available under the [MIT License](LICENSE).
