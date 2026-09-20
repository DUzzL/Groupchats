# Test report

Test environment: Minecraft 26.2, Fabric Loader 0.19.5, Fabric API 0.161.0+26.2, Java 25 and Gradle 9.5.1.

## Automated tests

`./gradlew test`: **62 tests passed, 0 failures**.

Coverage includes:

- Names: allowed characters, length boundaries, numbers, reserved command words and case-insensitive uniqueness.
- Ownership limits, per-player overrides, a zero quota, and regular/administrative transfers with limit changes before confirmation.
- Member, co-owner and owner permissions; prevention of co-owner privilege escalation.
- Invitations: sender/recipient cooldown across groups, independent senders and recipients, cooldown preservation after acceptance/decline, and a disabled cooldown.
- Offline invitations: persistence, renaming, exact expiry boundaries and reinvitation.
- Personal colors and aliases: per-player uniqueness, persistence and behavior after renaming.
- Leaving and kicking: role and preference cleanup.
- Confirmations: actor, action, group, target, 30-second boundary, changes to group state, disconnect and restart.
- Transfers: existing-member requirement, former-owner demotion and limit rechecks.
- Persistence: UUID identity, backups, damaged data, future schemas and rollback after write failures.
- TOML parsing, disabled-by-default logging, explicit positive retention settings, and validation without overwriting invalid configuration. Zero retention reports how to use `-1` instead.
- Configuration migration: custom values/comments, TOML root settings, precedence of the new location, and preservation of invalid legacy files.
- Translation of old generated configuration comments, preservation of custom comments and values, idempotence, indentation and Windows line endings.
- Separate group logs, complete metadata, escaped message contents and a stable file after renaming.
- Rolling message retention, exact expiry, changed retention after restart and cleanup of inactive/deleted groups.
- Concurrent appending/cleanup, write errors, unrelated files, orphaned temporary files and damaged logs.
- Disabled logging: no directory creation or writes, no access to an unusable log location, preservation of existing logs, and resumed cleanup when logging is re-enabled.

## Integration on a running server

The harness uses an actual Fabric 26.2 dedicated server with simulated `ServerPlayer` connections. It captures and checks the Minecraft chat packets sent to those players.

| Run | Passed assertions |
| --- | --- |
| Default logging disabled (`-1`), without LuckPerms | 16 |
| Logging explicitly enabled (`24`), with LuckPerms Fabric 5.5.85 and Fabric Permissions API 0.7.0 | 219 |

The disabled run checks generated defaults, absence of a cleanup thread and log directory, private message delivery, aliases, and continued delivery with an unusable log path. Its sentinel file also remains unchanged after server shutdown. The enabled run includes 96 base assertions and 123 LuckPerms checks.

Coverage includes both command roots, OP level 3/4 permissions, console administration, renaming, co-owner roles, tab completion, invitation click actions, offline invitations on login, personal colors and aliases, bold group names, exclusion of non-members, transfers and deletion. English success messages, command errors, invitation text and button labels are checked explicitly.

Log checks cover the configuration directory, complete message metadata, one log entry per message regardless of recipient count, separation between groups, renaming, rejected messages and log-write failures. An actual scheduled cleanup cycle verifies that expired logs for a deleted group disappear without new chat activity while recent entries remain. The intentionally triggered log-write error in the test server's output is expected.

LuckPerms coverage checks every player permission with both command roots, explicit denials after parsing, wildcard denial with an individual exception, separate administrator grants without OP, explicit administrative denies despite OP level 4, preservation of group roles, rank-inherited limits, invalid metadata, zero quotas, quota changes at confirmation, revoked transfer permissions and saved metadata for offline recipients. It also checks that an administrator granted only list access cannot obtain group members through ordinary management suggestions.

The additional controlled provider suite passed 109 assertions in the previous release and remains available when logging is enabled without LuckPerms. It checks pending lookup feedback, rejection of duplicate pending requests, permission revocation while a lookup is running, immediate and deferred failures, the 10-second timeout, recovery after timeout and disconnect cancellation. The deliberately injected errors and timeout in that test log are expected.

The earlier review reproduced a console completion `NullPointerException` before the fix. Its regression assertion remains part of the enabled-logging integration suite. The release archive was checked for its server-only metadata and three bundled dependencies, with no smoke-test classes or LuckPerms implementation bundled.

No graphical vanilla client was connected. A manual visual and click test on the target server remains outstanding, particularly for interactions with other mods.

## Repeating the integration test

Run only in a separate development environment. The harness performs administrative actions automatically and then stops the test server. It must not be installed on a player server or included in the release JAR.

1. Run `./gradlew smokeJar`.
2. Create a new, empty test directory with a `mods/` subdirectory. Copy `build/libs/groupchat-smoke.jar` into it.
3. Create a `server.properties` in the test directory with `server-ip=127.0.0.1`, an available `server-port`, `online-mode=false`, `view-distance=2`, `simulation-distance=2` and `max-tick-time=-1`. The latter prevents the watchdog from ending the intentional wait for scheduled cleanup.
4. Review and accept the Minecraft EULA for this test server (`eula=true` in its `eula.txt`).
5. Run `./gradlew runServer -PsmokeRunDir=/absolute/path/to/test-directory`. The development launch loads the main mod and Fabric API automatically.
6. With no pre-existing config, the test directory's `smoke-result.txt` must begin with `PASS: 16`. The disabled-logging test leaves a sentinel file at `config/groupchats/logs` to verify that shutdown does not access it. A successful Gradle exit alone is not proof that the assertions passed.
7. Repeat in another fresh directory with `LuckPerms-Fabric-5.5.85.jar` in `mods/` and a `config/groupchats/groupchat.toml` containing `chat_log_retention_hours = 24`. This selects the full logging and LuckPerms suite, including an actual scheduled cleanup cycle. That run must produce `PASS: 219`.
8. To run the controlled provider suite, use a fresh directory with the same explicit `24` setting but without LuckPerms. The expected result is `PASS: 109`.

Use a fresh test directory for every run. The harness uses fixed player names and expects an empty group database.
