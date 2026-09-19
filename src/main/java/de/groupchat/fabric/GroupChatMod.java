package de.groupchat.fabric;

import com.google.gson.JsonParser;
import de.groupchat.core.GcConfig;
import de.groupchat.core.GroupService;
import de.groupchat.core.ChatLogStore;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Files;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

public final class GroupChatMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("groupchat");
    private GroupService service;
    private ChatLogStore chatLogs;
    private ScheduledExecutorService logCleanup;
    public GroupService service() {
        if (service == null) throw new IllegalStateException("GroupChat has not started yet.");
        return service;
    }
    public void logMessage(GroupService.GroupView group, UUID sender, String senderName, String message) {
        try {
            chatLogs.append(group, sender, senderName, message);
        } catch (java.io.IOException e) {
            LOGGER.error("Could not log the group message; the message was not sent.", e);
            throw new de.groupchat.core.GcException("Log storage error: your message was not sent. Please contact an administrator.");
        }
    }
    @Override public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> new GcCommands(this).register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTING.register(this::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stopLogs());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { service = null; chatLogs = null; });
        ServerPlayConnectionEvents.JOIN.register((listener, sender, server) -> {
            try {
                var player = listener.player;
                service().rememberPlayers(Map.of(player.getUUID(), player.getName().getString()));
                for (var invitation : service().invitations(player.getUUID()))
                    player.sendSystemMessage(ChatText.invitation(invitation, service().playerName(invitation.inviter())));
            } catch (RuntimeException e) {
                LOGGER.error("GroupChat could not process player information or invitations", e);
                listener.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[GC] Could not load invitations. Please contact an administrator."));
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> {
            if (service != null) service.forgetConfirmations(listener.player.getUUID());
        });
    }
    private void start(MinecraftServer server) {
        try {
            var configDirectory = FabricLoader.getInstance().getConfigDir();
            GcConfig config = GcConfig.loadFromConfigDirectory(configDirectory);
            service = new GroupService(server.getWorldPath(LevelResource.ROOT).resolve("data/groupchat.json"), config, Clock.systemUTC());
            importKnownPlayers(server);
            chatLogs = new ChatLogStore(configDirectory.resolve("groupchats/logs"), config.chatLogRetentionHours(), Clock.systemUTC());
            logCleanup = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "groupchat-log-cleanup");
                thread.setDaemon(true);
                return thread;
            });
            logCleanup.scheduleWithFixedDelay(this::cleanupLogs, 60, 60, TimeUnit.SECONDS);
            LOGGER.info("GroupChat started: {} groups; ownership limit {}; invitation cooldown {}s; chat log retention {}h.",
                    service.groups().size(), config.maxOwnedGroups(), config.inviteCooldownSeconds(), config.chatLogRetentionHours());
        } catch (Exception e) {
            throw new IllegalStateException("GroupChat could not start. Check the configuration, data file and logs; existing data has been preserved.", e);
        }
    }
    private void cleanupLogs() {
        try { chatLogs.cleanup(); }
        catch (Exception e) { LOGGER.error("GroupChat log cleanup failed. Check the affected files; retrying in one minute.", e); }
    }
    private void stopLogs() {
        if (logCleanup == null) return;
        logCleanup.shutdown();
        try {
            if (!logCleanup.awaitTermination(5, TimeUnit.SECONDS)) logCleanup.shutdownNow();
        } catch (InterruptedException e) {
            logCleanup.shutdownNow(); Thread.currentThread().interrupt();
        }
        cleanupLogs();
        logCleanup = null;
    }
    private void importKnownPlayers(MinecraftServer server) throws java.io.IOException {
        var file = server.getServerDirectory().resolve("usercache.json");
        if (Files.notExists(file)) return;
        Map<UUID, String> players = new LinkedHashMap<>();
        try {
            var entries = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
            for (var element : entries) {
                var entry = element.getAsJsonObject();
                try {
                    // JOIN refreshes known identities; only import missing cache entries.
                    UUID id = UUID.fromString(entry.get("uuid").getAsString());
                    String name = entry.get("name").getAsString();
                    if (name.matches("[A-Za-z0-9_]{1,16}") && !service.knownPlayers().containsKey(id)
                            && service.knownPlayers().values().stream().noneMatch(name::equalsIgnoreCase)) players.put(id, name);
                } catch (RuntimeException e) { LOGGER.warn("Skipped an invalid entry in usercache.json."); }
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Could not read usercache.json; new players will be registered when they join.", e);
        }
        if (!players.isEmpty()) service.rememberPlayers(players);
    }
}
