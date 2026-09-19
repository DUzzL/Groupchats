package de.groupchat.fabric;

import me.lucko.fabric.api.permissions.v0.Options;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class GroupPermissions {
    public static final String OWNERSHIP_META = "groupchat.max-owned-groups";
    private GroupPermissions() {}

    public static String node(String action) {
        return "groupchat." + (action.startsWith("admin.") ? action : "command." + action);
    }
    public static boolean allowed(CommandSourceStack source, String action) {
        boolean fallback = !action.startsWith("admin.")
                || source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_OWNER);
        return Permissions.check(source, node(action), fallback);
    }
    public static boolean anyAdmin(CommandSourceStack source) {
        return allowed(source, "admin.list") || allowed(source, "admin.info")
                || allowed(source, "admin.delete") || allowed(source, "admin.transfer");
    }
    public static CompletableFuture<Integer> ownershipLimit(MinecraftServer server, UUID player, int fallback) {
        var online = server.getPlayerList().getPlayer(player);
        if (online != null) return CompletableFuture.completedFuture(parseLimit(Options.get(online, OWNERSHIP_META).orElse(null), fallback));
        return Options.get(player, OWNERSHIP_META).thenApply(value -> parseLimit(value.orElse(null), fallback));
    }
    private static int parseLimit(String value, int fallback) {
        if (value == null) return fallback;
        try {
            int limit = Integer.parseInt(value.strip());
            if (limit >= 0 && limit <= 100_000) return limit;
        } catch (NumberFormatException ignored) { }
        GroupChatMod.LOGGER.warn("Invalid {} value '{}'; using the configured limit {}.", OWNERSHIP_META, value, fallback);
        return fallback;
    }
}
