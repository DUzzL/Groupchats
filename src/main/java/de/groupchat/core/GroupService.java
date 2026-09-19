package de.groupchat.core;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import static de.groupchat.core.State.*;

/** Access only from the Minecraft server thread. */
public final class GroupService {
    public static final Set<String> RESERVED = Set.of("gc", "groupchat", "create", "rename", "delete", "confirm",
            "invite", "kick", "transfer", "accept", "decline", "leave", "shorten", "color", "coowner", "add",
            "remove", "list", "info", "help", "admin");
    public static final List<String> COLORS = List.of("black", "dark_blue", "dark_green", "dark_aqua", "dark_red",
            "dark_purple", "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{3,20}");
    private static final Pattern ALIAS = Pattern.compile("[A-Za-z0-9_-]{1,2}");
    private final GcConfig config;
    private final Clock clock;
    private final StateStore store;
    private State state;
    private final Map<String, Confirmation> confirmations = new HashMap<>();

    public record GroupView(UUID id, String name, UUID owner, Set<UUID> members, Set<UUID> coowners) {
        public boolean manages(UUID player) { return owner.equals(player) || coowners.contains(player); }
    }
    public record PreferenceView(String alias, String color) {}
    public record InvitationView(GroupView group, UUID inviter, long expiresAt) {}
    private record Confirmation(String operation, UUID groupId, UUID target, long revision, long expiresAt) {}

    public GroupService(Path file, GcConfig config, Clock clock) throws IOException {
        this.config = config;
        this.clock = clock;
        this.store = new StateStore(file);
        this.state = store.load();
    }

    public int defaultOwnershipLimit() { return config.maxOwnedGroups(); }

    public static String key(String value) { return value.toLowerCase(Locale.ROOT); }
    public static void checkName(String name) {
        if (name == null || !NAME.matcher(name).matches())
            fail("Group names must contain 3–20 characters: A–Z, a–z, 0–9, _ or -.");
        if (RESERVED.contains(key(name))) fail("This name is reserved for a command.");
    }
    private static void fail(String message) { throw new GcException(message); }
    private long now() { return clock.millis(); }
    private GroupView view(Group group) {
        return new GroupView(group.id, group.name, group.owner, Set.copyOf(group.members), Set.copyOf(group.coowners));
    }
    public List<GroupView> groups() {
        return state.groups.values().stream().map(this::view).sorted(Comparator.comparing(g -> key(g.name()))).toList();
    }
    public List<GroupView> groupsFor(UUID player) { return groups().stream().filter(g -> g.members.contains(player)).toList(); }
    public GroupView find(String name) { return view(group(name)); }
    private Group group(String name) {
        return state.groups.values().stream().filter(g -> g.name.equalsIgnoreCase(name)).findFirst()
                .orElseThrow(() -> new GcException("This group does not exist."));
    }
    private Group member(UUID player, String name) {
        Group group = group(name);
        if (!group.members.contains(player)) fail("You are not a member of this group.");
        return group;
    }
    private Group manager(UUID player, String name) {
        Group group = member(player, name);
        if (!group.owner.equals(player) && !group.coowners.contains(player)) fail("Only the owner and co-owners can do that.");
        return group;
    }
    private Group owner(UUID player, String name) {
        Group group = member(player, name);
        if (!group.owner.equals(player)) fail("Only the owner can do that.");
        return group;
    }
    public GroupView forChat(UUID player, String nameOrAlias) {
        for (Group group : state.groups.values()) {
            Preference pref = group.preferences.get(player);
            if (group.members.contains(player) && (group.name.equalsIgnoreCase(nameOrAlias)
                    || pref != null && pref.alias != null && pref.alias.equalsIgnoreCase(nameOrAlias))) return view(group);
        }
        throw new GcException("You do not belong to a group with that name or alias.");
    }
    public PreferenceView preference(UUID player, UUID groupId) {
        Group group = state.groups.get(groupId);
        if (group == null || !group.members.contains(player)) fail("You are not a member of this group.");
        Preference pref = group.preferences.get(player);
        return pref == null ? new PreferenceView(null, "white") : new PreferenceView(pref.alias, pref.color);
    }
    public String playerName(UUID id) { return state.players.getOrDefault(id, id.toString()); }
    public Map<UUID, String> knownPlayers() { return Map.copyOf(state.players); }
    public UUID playerId(String name) {
        return state.players.entrySet().stream().filter(e -> e.getValue().equalsIgnoreCase(name)).map(Map.Entry::getKey)
                .findFirst().orElseThrow(() -> new GcException("Unknown player. The player must already be known to this server."));
    }
    public void rememberPlayers(Map<UUID, String> players) {
        if (players.entrySet().stream().allMatch(e -> e.getValue().equals(state.players.get(e.getKey())))) return;
        change(() -> {
            // Names can change owners; memberships follow UUIDs.
            for (var entry : players.entrySet()) {
                state.players.entrySet().removeIf(e -> !e.getKey().equals(entry.getKey()) && e.getValue().equalsIgnoreCase(entry.getValue()));
                state.players.put(entry.getKey(), entry.getValue());
            }
            return null;
        });
    }
    private void checkLimit(UUID owner, int limit) {
        if (limit < 0 || limit > 100_000) throw new IllegalArgumentException("Invalid ownership limit.");
        if (state.groups.values().stream().filter(g -> g.owner.equals(owner)).count() >= limit)
            fail("The ownership limit of " + limit + " groups has been reached.");
    }
    public GroupView create(UUID player, String name) {
        return create(player, name, config.maxOwnedGroups());
    }
    public GroupView create(UUID player, String name, int ownershipLimit) {
        checkName(name);
        if (state.groups.values().stream().anyMatch(g -> g.name.equalsIgnoreCase(name))) fail("This group name is already taken.");
        checkLimit(player, ownershipLimit);
        return change(() -> {
            Group group = new Group();
            group.id = UUID.randomUUID(); group.name = name; group.owner = player; group.members.add(player);
            state.groups.put(group.id, group);
            return view(group);
        });
    }
    public GroupView rename(UUID actor, String oldName, String newName) {
        Group group = manager(actor, oldName);
        checkName(newName);
        if (state.groups.values().stream().anyMatch(g -> !g.id.equals(group.id) && g.name.equalsIgnoreCase(newName)))
            fail("This group name is already taken.");
        return change(() -> { group.name = newName; group.revision++; return view(group); });
    }
    public InvitationView invite(UUID actor, String name, UUID target) {
        Group group = manager(actor, name);
        if (group.members.contains(target)) fail("This player is already a member.");
        Invite pending = group.invitations.get(target);
        if (pending != null && pending.expiresAt > now()) fail("This player already has a pending invitation.");
        String pair = actor + ":" + target;
        Long previous = state.lastInvites.get(pair);
        long until = previous == null ? 0 : previous + config.inviteCooldownSeconds() * 1000;
        if (until > now()) fail("You can invite this player again in " + ((until - now() + 999) / 1000) + " seconds.");
        return change(() -> {
            Invite invite = new Invite(actor, now() + config.inviteExpiryDays() * 86_400_000L);
            group.invitations.put(target, invite);
            state.lastInvites.put(pair, now());
            return new InvitationView(view(group), actor, invite.expiresAt);
        });
    }
    public List<InvitationView> invitations(UUID player) {
        return state.groups.values().stream().filter(g -> g.invitations.containsKey(player))
                .filter(g -> g.invitations.get(player).expiresAt > now())
                .map(g -> new InvitationView(view(g), g.invitations.get(player).inviter, g.invitations.get(player).expiresAt))
                .sorted(Comparator.comparing(i -> key(i.group.name))).toList();
    }
    public GroupView respond(UUID player, String name, boolean accept) {
        Group group = group(name);
        Invite invite = group.invitations.get(player);
        if (invite == null || invite.expiresAt <= now()) fail("You have no valid invitation to this group.");
        return change(() -> {
            group.invitations.remove(player);
            if (accept) { group.members.add(player); group.revision++; }
            return view(group);
        });
    }
    public GroupView leave(UUID player, String name) {
        Group group = member(player, name);
        if (group.owner.equals(player)) fail("Transfer or delete the group before leaving it.");
        return change(() -> { removeMember(group, player); return view(group); });
    }
    public GroupView kick(UUID actor, String name, UUID target) {
        Group group = manager(actor, name);
        if (!group.members.contains(target)) fail("This player is not a member.");
        if (group.owner.equals(target)) fail("The owner cannot be kicked.");
        if (target.equals(actor)) fail("Use /gc leave " + group.name + " to leave the group.");
        if (!group.owner.equals(actor) && group.coowners.contains(target)) fail("Co-owners cannot kick other co-owners.");
        return change(() -> { removeMember(group, target); return view(group); });
    }
    private void removeMember(Group group, UUID target) {
        group.members.remove(target); group.coowners.remove(target); group.preferences.remove(target);
        group.invitations.entrySet().removeIf(e -> e.getValue().inviter.equals(target));
        group.revision++;
    }
    public GroupView coowner(UUID actor, String name, UUID target, boolean add) {
        Group group = owner(actor, name);
        if (!group.members.contains(target)) fail("Co-owners must already be group members.");
        if (group.owner.equals(target)) fail("The owner cannot be made a co-owner.");
        if (group.coowners.contains(target) == add) fail(add ? "This player is already a co-owner." : "This player is not a co-owner.");
        return change(() -> {
            if (add) group.coowners.add(target);
            else {
                group.coowners.remove(target);
                group.invitations.entrySet().removeIf(e -> e.getValue().inviter.equals(target));
            }
            group.revision++;
            return view(group);
        });
    }
    public void shorten(UUID player, String name, String alias) {
        Group group = member(player, name);
        if (alias != null) {
            if (!ALIAS.matcher(alias).matches() || RESERVED.contains(key(alias)))
                fail("Aliases must contain 1–2 characters: A–Z, a–z, 0–9, _ or -. Command names are reserved.");
            if (state.groups.values().stream().anyMatch(g -> !g.id.equals(group.id) && g.preferences.containsKey(player)
                    && g.preferences.get(player).alias != null && g.preferences.get(player).alias.equalsIgnoreCase(alias)))
                fail("You already use this alias for another group.");
        }
        change(() -> { group.preferences.computeIfAbsent(player, k -> new Preference()).alias = alias; return null; });
    }
    public void color(UUID player, String name, String color) {
        Group group = member(player, name);
        String normalized = key(color);
        if (!COLORS.contains(normalized)) fail("Invalid color. Press Tab to choose a Minecraft color.");
        change(() -> { group.preferences.computeIfAbsent(player, k -> new Preference()).color = normalized; return null; });
    }
    /** Returns false when confirmation was requested, true when the action completed. */
    public boolean delete(UUID actor, String name, boolean admin, boolean confirm) {
        Group group = admin ? group(name) : owner(actor, name);
        if (!confirmed(actor, admin, "delete", group, null, confirm)) return false;
        change(() -> { state.groups.remove(group.id); return null; });
        return true;
    }
    public boolean transfer(UUID actor, String name, UUID target, boolean admin, boolean confirm) {
        return transfer(actor, name, target, admin, confirm, config.maxOwnedGroups());
    }
    public boolean transfer(UUID actor, String name, UUID target, boolean admin, boolean confirm, int ownershipLimit) {
        Group group = admin ? group(name) : owner(actor, name);
        if (!group.members.contains(target)) fail("The new owner must already be a member.");
        if (group.owner.equals(target)) fail("This player is already the owner.");
        checkLimit(target, ownershipLimit);
        if (!confirmed(actor, admin, "transfer", group, target, confirm)) return false;
        change(() -> {
            group.coowners.remove(target);
            group.owner = target;
            group.revision++;
            return null;
        });
        return true;
    }
    private boolean confirmed(UUID actor, boolean admin, String operation, Group group, UUID target, boolean confirm) {
        confirmations.values().removeIf(c -> c.expiresAt <= now());
        String actorKey = (admin ? "admin:" : "player:") + (actor == null ? "console" : actor);
        if (!confirm) {
            confirmations.put(actorKey, new Confirmation(operation, group.id, target, group.revision, now() + 30_000));
            return false;
        }
        Confirmation pending = confirmations.remove(actorKey);
        if (pending == null || !pending.operation.equals(operation) || !pending.groupId.equals(group.id)
                || !Objects.equals(pending.target, target) || pending.revision != group.revision)
            fail("Confirmation is missing, has expired, or the group has changed. Run the command without confirm first.");
        return true;
    }
    public void forgetConfirmations(UUID actor) {
        confirmations.remove("admin:" + actor); confirmations.remove("player:" + actor);
    }
    private <T> T change(Supplier<T> action) {
        State before = StateStore.JSON.fromJson(StateStore.JSON.toJson(state), State.class);
        try {
            long time = now();
            state.lastInvites.entrySet().removeIf(e -> e.getValue() + config.inviteCooldownSeconds() * 1000 <= time);
            state.groups.values().forEach(g -> g.invitations.entrySet().removeIf(e -> e.getValue().expiresAt <= time));
            T value = action.get();
            store.save(state);
            return value;
        } catch (IOException e) {
            state = before;
            throw new java.io.UncheckedIOException("GroupChat could not save the change. It has been rolled back.", e);
        } catch (RuntimeException e) {
            state = before;
            throw e;
        }
    }

    static void validate(State state) {
        if (state == null || state.schemaVersion != 1 || state.groups == null || state.players == null || state.lastInvites == null)
            throw new IllegalStateException("Unsupported data schema");
        Set<String> names = new HashSet<>();
        Map<UUID, Set<String>> aliases = new HashMap<>();
        for (var entry : state.groups.entrySet()) {
            Group g = entry.getValue();
            if (g == null || g.id == null || !g.id.equals(entry.getKey()) || g.owner == null || g.members == null || g.coowners == null
                    || g.invitations == null || g.preferences == null || !g.members.contains(g.owner) || g.members.contains(null)
                    || !g.members.containsAll(g.coowners) || g.coowners.contains(g.owner)) throw new IllegalStateException("Invalid group");
            checkName(g.name);
            if (!names.add(key(g.name))) throw new IllegalStateException("Duplicate group name");
            for (var pref : g.preferences.entrySet()) {
                Preference p = pref.getValue();
                if (!g.members.contains(pref.getKey()) || p == null || !COLORS.contains(p.color)) throw new IllegalStateException("Invalid preference");
                if (p.alias != null && (!ALIAS.matcher(p.alias).matches() || RESERVED.contains(key(p.alias))
                        || !aliases.computeIfAbsent(pref.getKey(), k -> new HashSet<>()).add(key(p.alias))))
                    throw new IllegalStateException("Invalid or duplicate alias");
            }
            for (var invite : g.invitations.entrySet()) {
                if (invite.getKey() == null || invite.getValue() == null || invite.getValue().inviter == null
                        || !g.members.contains(invite.getValue().inviter) || g.members.contains(invite.getKey()))
                    throw new IllegalStateException("Invalid invitation");
            }
        }
        if (state.players.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getValue() == null)
                || state.lastInvites.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getValue() == null))
            throw new IllegalStateException("Invalid player directory");
    }
}
