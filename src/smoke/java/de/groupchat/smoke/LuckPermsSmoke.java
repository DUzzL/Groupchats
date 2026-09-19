package de.groupchat.smoke;

import de.groupchat.core.GroupService;
import de.groupchat.fabric.GroupPermissions;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.MetaNode;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import static de.groupchat.smoke.ServerSmoke.*;

final class LuckPermsSmoke {
    static User load(UUID id, String name) {
        return LuckPermsProvider.get().getUserManager().loadUser(id, name).join();
    }
    static User user(TestPlayer player) {
        return Objects.requireNonNull(LuckPermsProvider.get().getUserManager().getUser(player.player().getUUID()));
    }
    static void permission(User user, String node, Boolean value) {
        user.data().clear(n -> n.getKey().equals(node));
        if (value != null) user.data().add(Node.builder(node).value(value).build());
        user.getCachedData().invalidate();
    }
    static void meta(User user, String value) {
        user.data().clear(n -> n instanceof MetaNode m && m.getMetaKey().equals(GroupPermissions.OWNERSHIP_META));
        if (value != null) user.data().add(MetaNode.builder(GroupPermissions.OWNERSHIP_META, value).build());
        user.getCachedData().invalidate();
    }
    static void waitFor(MinecraftServer server, BooleanSupplier condition, String label) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        server.managedBlock(() -> condition.getAsBoolean() || System.nanoTime() >= deadline);
        check(condition.getAsBoolean(), label);
    }
    static void offlineMeta(UUID id, String name, String value) {
        var user = load(id, name);
        meta(user, value);
        LuckPermsProvider.get().getUserManager().saveUser(user).join();
        LuckPermsProvider.get().getUserManager().cleanupUser(user);
    }
    static void run(MinecraftServer server, GroupService service) throws Exception {
        var d = server.getCommands().getDispatcher();
        var owner = player(server, "PermOwner");
        var member = player(server, "PermMember");
        var moderator = player(server, "PermModerator");
        var quota = player(server, "PermQuota");
        ok(d, owner.source(), "gc create PermBase");
        ok(d, owner.source(), "gc invite PermBase PermMember");
        ok(d, member.source(), "gc accept PermBase");
        var commands = new LinkedHashMap<String, String>();
        commands.put("help", "help"); commands.put("list", "list"); commands.put("info", "info PermBase");
        commands.put("create", "create DeniedGroup"); commands.put("rename", "rename PermBase DeniedName");
        commands.put("invite", "invite PermBase PermModerator"); commands.put("kick", "kick PermBase PermMember");
        commands.put("accept", "accept PermBase"); commands.put("decline", "decline PermBase");
        commands.put("leave", "leave PermBase"); commands.put("shorten", "shorten PermBase p");
        commands.put("color", "color PermBase red"); commands.put("coowner.add", "coowner add PermBase PermMember");
        commands.put("coowner.remove", "coowner remove PermBase PermMember"); commands.put("delete", "delete PermBase");
        commands.put("transfer", "transfer PermBase PermMember"); commands.put("chat", "PermBase secret");
        for (var entry : commands.entrySet()) {
            String permission = GroupPermissions.node(entry.getKey());
            var parsed = d.parse("groupchat " + entry.getValue(), owner.source());
            permission(user(owner), permission, false);
            fail(d, owner.source(), "gc " + entry.getValue());
            fail(d, owner.source(), "groupchat " + entry.getValue());
            check(d.execute(parsed) == 0, "Permission rechecked after parsing: " + permission);
            check(!GroupPermissions.allowed(owner.source(), entry.getKey()), "Explicit false: " + permission);
            permission(user(owner), permission, null);
        }
        check(service.find("PermBase").members().contains(member.player().getUUID()), "Denied commands preserve membership");
        check(service.groups().size() == 1, "Denied create/rename/delete preserve groups");
        permission(user(owner), "groupchat.command.help", false);
        fail(d, owner.source(), "gc"); fail(d, owner.source(), "groupchat");
        permission(user(owner), "groupchat.command.help", null);
        permission(user(owner), "groupchat.command.*", false);
        fail(d, owner.source(), "gc list");
        permission(user(owner), "groupchat.command.list", true);
        ok(d, owner.source(), "groupchat list");
        permission(user(owner), "groupchat.command.*", null);
        permission(user(owner), "groupchat.command.list", null);

        permission(user(moderator), "groupchat.admin.list", true);
        ok(d, moderator.source(), "gc admin list");
        check(suggestions(d, moderator.source(), "gc invite PermBase ").isEmpty(), "Admin list alone does not reveal invite candidates for unmanaged groups");
        check(suggestions(d, moderator.source(), "gc transfer PermBase ").isEmpty(), "Admin list alone does not reveal members through normal transfer");
        fail(d, moderator.source(), "gc admin info PermBase");
        fail(d, moderator.source(), "gc admin delete PermBase");
        fail(d, moderator.source(), "gc admin transfer PermBase PermMember");
        permission(user(moderator), "groupchat.admin.info", true);
        ok(d, moderator.source(), "groupchat admin info PermBase");
        permission(user(moderator), "groupchat.command.*", true);
        fail(d, moderator.source(), "gc rename PermBase Stolen");
        fail(d, moderator.source(), "gc PermBase secret");
        for (String action : List.of("admin.list", "admin.info", "admin.delete", "admin.transfer")) {
            String tail = switch (action) {
                case "admin.list" -> "list";
                case "admin.info" -> "info PermBase";
                case "admin.delete" -> "delete PermBase";
                default -> "transfer PermBase PermMember";
            };
            var op = owner.source().withPermission(LevelBasedPermissionSet.OWNER);
            var parsed = d.parse("gc admin " + tail, op);
            permission(user(owner), GroupPermissions.node(action), false);
            fail(d, op, "gc admin " + tail);
            check(d.execute(parsed) == 0, "Admin explicit deny overrides OP and cached parse: " + action);
            permission(user(owner), GroupPermissions.node(action), null);
        }

        var rank = LuckPermsProvider.get().getGroupManager().createAndLoadGroup("gc-vip").join();
        rank.data().add(MetaNode.builder(GroupPermissions.OWNERSHIP_META, "4").build());
        user(quota).data().add(InheritanceNode.builder("gc-vip").build());
        user(quota).getCachedData().invalidate();
        for (int i = 1; i <= 4; i++) ok(d, quota.source(), "gc create Quota" + i);
        fail(d, quota.source(), "gc create Quota5");
        check(GroupPermissions.ownershipLimit(server, quota.player().getUUID(), 3).join() == 4, "Inherited rank meta overrides TOML");
        user(quota).data().clear(n -> n instanceof InheritanceNode i && i.getGroupName().equals("gc-vip"));
        user(quota).getCachedData().invalidate();
        for (String value : List.of("invalid", "-1", "100001", "999999999999999")) {
            meta(user(quota), value);
            check(GroupPermissions.ownershipLimit(server, quota.player().getUUID(), 3).join() == 3, "Invalid meta falls back: " + value);
        }
        meta(user(member), "0");
        fail(d, member.source(), "gc create ZeroLimit");
        ok(d, member.source(), "gc PermBase Membership remains available");
        meta(user(member), "1");
        ok(d, owner.source(), "gc transfer PermBase PermMember");
        meta(user(member), "0");
        fail(d, owner.source(), "gc transfer PermBase PermMember confirm");
        check(service.find("PermBase").owner().equals(owner.player().getUUID()), "Meta rechecked at transfer confirmation");
        meta(user(member), "1");
        ok(d, owner.source(), "gc transfer PermBase PermMember");
        permission(user(owner), "groupchat.command.transfer", false);
        fail(d, owner.source(), "gc transfer PermBase PermMember confirm");
        check(service.find("PermBase").owner().equals(owner.player().getUUID()), "Revoked transfer permission prevents confirmation");
        permission(user(owner), "groupchat.command.transfer", null);

        UUID offline = UUID.nameUUIDFromBytes("OfflinePlayer:PermOffline".getBytes(StandardCharsets.UTF_8));
        service.rememberPlayers(Map.of(offline, "PermOffline"));
        service.create(offline, "OfflineOwn");
        service.invite(owner.player().getUUID(), "PermBase", offline);
        service.respond(offline, "PermBase", true);
        offlineMeta(offline, "PermOffline", "1");
        int before = owner.connection().messages.size();
        d.execute("gc transfer PermBase PermOffline", owner.source());
        waitFor(server, () -> owner.connection().messages.stream().skip(before)
                .anyMatch(m -> m.getString().contains("ownership limit of 1")), "Offline recipient's stored meta enforced");
        check(service.find("PermBase").owner().equals(owner.player().getUUID()), "Offline limit prevents transfer");
        offlineMeta(offline, "PermOffline", "2");
        int promptBefore = owner.connection().messages.size();
        ok(d, owner.source(), "gc transfer PermBase PermOffline");
        waitFor(server, () -> owner.connection().messages.stream().skip(promptBefore)
                .anyMatch(m -> m.getString().contains("To confirm the transfer")), "Offline lookup produces confirmation prompt");
        ok(d, owner.source(), "groupchat transfer PermBase PermOffline confirm");
        waitFor(server, () -> service.find("PermBase").owner().equals(offline), "Offline transfer completed with meta override");

        permission(user(moderator), "groupchat.admin.delete", true);
        ok(d, moderator.source(), "gc admin delete PermBase");
        ok(d, moderator.source(), "groupchat admin delete PermBase confirm");
        permission(user(moderator), "groupchat.admin.transfer", true);
        service.invite(quota.player().getUUID(), "Quota1", member.player().getUUID());
        service.respond(member.player().getUUID(), "Quota1", true);
        ok(d, moderator.source(), "gc admin transfer Quota1 PermMember");
        ok(d, moderator.source(), "gc admin transfer Quota1 PermMember confirm");
        check(service.find("Quota1").owner().equals(member.player().getUUID()), "Separate admin transfer grant works without OP");
    }
}
