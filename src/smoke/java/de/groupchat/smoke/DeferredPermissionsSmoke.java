package de.groupchat.smoke;

import de.groupchat.core.GroupService;
import de.groupchat.fabric.GroupPermissions;
import me.lucko.fabric.api.permissions.v0.OfflineOptionRequestEvent;
import me.lucko.fabric.api.permissions.v0.PermissionCheckEvent;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.server.MinecraftServer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.*;
import static de.groupchat.smoke.ServerSmoke.*;

final class DeferredPermissionsSmoke {
    static void run(MinecraftServer server, GroupService service) throws Exception {
        var d = server.getCommands().getDispatcher();
        var owner = player(server, "AsyncOwner");
        UUID offline = UUID.randomUUID();
        service.rememberPlayers(Map.of(offline, "AsyncOffline"));
        service.create(owner.player().getUUID(), "AsyncBase");
        service.invite(owner.player().getUUID(), "AsyncBase", offline);
        service.respond(offline, "AsyncBase", true);
        var lookup = new AtomicReference<CompletableFuture<Optional<String>>>();
        var deny = new AtomicBoolean();
        OfflineOptionRequestEvent.EVENT.register((id, key) -> id.equals(offline) && key.equals(GroupPermissions.OWNERSHIP_META)
                ? lookup.get() : CompletableFuture.completedFuture(Optional.empty()));
        PermissionCheckEvent.EVENT.register((source, node) -> deny.get() && node.equals("groupchat.command.transfer") ? TriState.FALSE : TriState.DEFAULT);

        lookup.set(new CompletableFuture<>());
        ok(d, owner.source(), "gc transfer AsyncBase AsyncOffline");
        check(owner.connection().messages.getLast().getString().contains("continue automatically"), "Pending lookup is announced");
        fail(d, owner.source(), "gc transfer AsyncBase AsyncOffline");
        deny.set(true);
        lookup.get().complete(Optional.of("3"));
        check(owner.connection().messages.getLast().getString().contains("do not have permission"), "Permission revoked during lookup is enforced");
        deny.set(false);

        lookup.set(new CompletableFuture<>());
        ok(d, owner.source(), "gc transfer AsyncBase AsyncOffline");
        lookup.get().completeExceptionally(new IllegalStateException("Simulated provider failure"));
        check(owner.connection().messages.getLast().getString().contains("Could not load permissions"), "Failed lookup does not use TOML fallback");
        lookup.set(CompletableFuture.failedFuture(new IllegalStateException("Simulated immediate provider failure")));
        fail(d, owner.source(), "gc transfer AsyncBase AsyncOffline");

        lookup.set(new CompletableFuture<>());
        ok(d, owner.source(), "gc transfer AsyncBase AsyncOffline");
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(13).toNanos();
        server.managedBlock(() -> owner.connection().messages.getLast().getString().contains("Could not load permissions") || System.nanoTime() >= deadline);
        check(owner.connection().messages.getLast().getString().contains("Could not load permissions"), "Stalled lookup times out without applying an action");

        lookup.set(new CompletableFuture<>());
        ok(d, owner.source(), "gc transfer AsyncBase AsyncOffline");
        lookup.get().complete(Optional.of("3"));
        check(owner.connection().messages.getLast().getString().contains("To confirm the transfer"), "A new lookup succeeds after timeout");
        lookup.set(new CompletableFuture<>());
        ok(d, owner.source(), "gc transfer AsyncBase AsyncOffline confirm");
        server.getPlayerList().getPlayersByUUID().remove(owner.player().getUUID());
        lookup.get().complete(Optional.of("3"));
        check(service.find("AsyncBase").owner().equals(owner.player().getUUID()), "Disconnect cancels pending ownership transfer");
        server.getPlayerList().getPlayersByUUID().put(owner.player().getUUID(), owner.player());
    }
}
