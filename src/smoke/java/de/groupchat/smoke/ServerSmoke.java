package de.groupchat.smoke;

import com.mojang.authlib.GameProfile;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import de.groupchat.fabric.GroupChatMod;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.*;
import net.minecraft.network.protocol.*;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.*;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import io.netty.channel.ChannelFutureListener;
import java.nio.file.*;
import java.util.*;

/** Server-only integration test with simulated player connections; no test code enters the release JAR. */
public final class ServerSmoke implements ModInitializer {
    static final List<TestPlayer> PLAYERS = new ArrayList<>();
    static int checks;
    record TestPlayer(ServerPlayer player, Capture connection, CommandSourceStack source) {}
    record Span(String text, Style style) {}
    static final class Capture extends Connection {
        final List<Component> messages = new ArrayList<>();
        final List<Packet<?>> packets = new ArrayList<>();
        Capture() { super(PacketFlow.SERVERBOUND); }
        void capture(Packet<?> packet) { packets.add(packet); if (packet instanceof ClientboundSystemChatPacket chat) messages.add(chat.content()); }
        /** Name this player's tab list currently shows for target, or null when vanilla's default applies. */
        String tabName(UUID target) {
            for (int i = packets.size() - 1; i >= 0; i--)
                if (packets.get(i) instanceof net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket info)
                    for (var e : info.entries()) if (e.profileId().equals(target)) return e.displayName() == null ? null : e.displayName().getString();
            return null;
        }
        long sounds() { return packets.stream().filter(p -> p instanceof net.minecraft.network.protocol.game.ClientboundSoundPacket).count(); }
        @Override public void send(Packet<?> packet) { capture(packet); }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener) { capture(packet); }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) { capture(packet); }
    }
    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                run(server);
                Files.writeString(Path.of("smoke-result.txt"), "PASS: " + checks + " integration assertions on Minecraft 26.3 / Fabric\n");
                System.out.println("GROUPCHAT_SMOKE_PASS " + checks);
            } catch (Throwable e) {
                e.printStackTrace();
                try { Files.writeString(Path.of("smoke-result.txt"), "FAIL: " + e + "\n"); } catch (Exception ignored) {}
                System.out.println("GROUPCHAT_SMOKE_FAIL " + e);
            } finally {
                for (var p : PLAYERS) {
                    server.getPlayerList().getPlayers().remove(p.player);
                    server.getPlayerList().getPlayersByUUID().remove(p.player.getUUID());
                }
                server.halt(false);
            }
        });
    }
    static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    static TestPlayer player(MinecraftServer server, String name) {
        var profile = new GameProfile(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8)), name);
        if (FabricLoader.getInstance().isModLoaded("luckperms")) LuckPermsSmoke.load(profile.id(), name);
        var p = new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault());
        var capture = new Capture();
        p.connection = new ServerGamePacketListenerImpl(server, capture, p, CommonListenerCookie.createInitial(profile, false));
        server.getPlayerList().getPlayers().add(p); server.getPlayerList().getPlayersByUUID().put(p.getUUID(), p);
        var result = new TestPlayer(p, capture, p.createCommandSourceStack());
        PLAYERS.add(result);
        ServerPlayConnectionEvents.JOIN.invoker().onPlayReady(p.connection, null, server);
        return result;
    }
    static void ok(CommandDispatcher<CommandSourceStack> d, CommandSourceStack source, String command) throws Exception {
        check(d.execute(command, source) == 1, "Command should succeed: " + command);
    }
    static void fail(CommandDispatcher<CommandSourceStack> d, CommandSourceStack source, String command) throws Exception {
        try { check(d.execute(command, source) == 0, "Command should fail: " + command); }
        catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) { checks++; }
    }
    static List<Span> spans(Component text) {
        var result = new ArrayList<Span>();
        text.visit((style, part) -> { result.add(new Span(part, style)); return Optional.empty(); }, Style.EMPTY);
        return result;
    }
    static List<String> suggestions(CommandDispatcher<CommandSourceStack> d, CommandSourceStack source, String input) throws Exception {
        return d.getCompletionSuggestions(d.parse(input, source)).get().getList().stream().map(s -> s.getText()).toList();
    }
    static boolean publicChat(TestPlayer p, String text) {
        return net.fabricmc.fabric.api.message.v1.ServerMessageEvents.ALLOW_CHAT_MESSAGE.invoker()
                .allowChatMessage(PlayerChatMessage.system(text), p.player, null);
    }
    /** Group WithoutLogs exists with owner + alice (alias w, green) when this runs. */
    static void focusMuteTabSmoke(MinecraftServer server, CommandDispatcher<CommandSourceStack> d, TestPlayer owner, TestPlayer alice, TestPlayer outside) throws Exception {
        UUID ownerId = owner.player.getUUID(), aliceId = alice.player.getUUID();
        // Tab tags: only fellow members see them, in the viewer's own alias/color.
        check("[w] GcOwner".equals(alice.connection.tabName(ownerId)), "Alice sees her alias tag on the owner in tab");
        check("[WithoutLogs] GcAlice".equals(owner.connection.tabName(aliceId)), "Owner without alias sees group name tag");
        check(outside.connection.tabName(ownerId) == null, "Outsider sees no group tags in tab");
        // Click to reply uses the recipient's alias.
        ok(d, owner.source, "gc WithoutLogs ping test");
        check(spans(alice.connection.messages.getLast()).stream().anyMatch(s -> new ClickEvent.SuggestCommand("/gc w ").equals(s.style.getClickEvent())), "Click suggests reply via alias");
        // Mentions ping only the mentioned player.
        long aliceSounds = alice.connection.sounds(), ownerSounds = owner.connection.sounds();
        ok(d, owner.source, "gc WithoutLogs hey @gcalice look");
        check(alice.connection.sounds() == aliceSounds + 1, "Mentioned player gets a ping");
        ok(d, owner.source, "gc WithoutLogs GcAlicex is not her");
        check(alice.connection.sounds() == aliceSounds + 1, "Partial name does not ping");
        check(owner.connection.sounds() == ownerSounds, "Sender is not pinged");
        // Focus mode routes public chat to the group and never leaks it.
        int outsideBefore = outside.connection.messages.size();
        check(publicChat(owner, "public before focus"), "Without focus, chat stays public");
        ok(d, owner.source, "gc focus WithoutLogs");
        check(!publicChat(owner, "secret base coords"), "Focused chat is cancelled publicly");
        check(alice.connection.messages.getLast().getString().equals("[WithoutLogs] [w] GcOwner: secret base coords"), "Focused chat reaches the group");
        check(outside.connection.messages.size() == outsideBefore, "Focused chat does not reach outsiders");
        ok(d, owner.source, "gc focus WithoutLogs");
        check(publicChat(owner, "public again"), "Focusing the same group again toggles it off");
        // Mute: muted member stops receiving chat, sender still sees own messages.
        ok(d, alice.source, "gc mute WithoutLogs");
        int aliceBefore = alice.connection.messages.size();
        ok(d, owner.source, "gc WithoutLogs while muted @GcAlice");
        check(alice.connection.messages.size() == aliceBefore, "Muted member receives no group chat");
        ok(d, alice.source, "gc WithoutLogs I can still talk");
        check(alice.connection.messages.getLast().getString().endsWith("I can still talk"), "Muted sender still sees own message");
        ok(d, alice.source, "gc mute WithoutLogs");
        ok(d, owner.source, "gc WithoutLogs after unmute");
        check(alice.connection.messages.getLast().getString().endsWith("after unmute"), "Unmute restores delivery");
        ok(d, alice.source, "gc focus w");
        ok(d, owner.source, "gc kick WithoutLogs GcAlice");
        check(alice.connection.tabName(ownerId) == null, "Kicked member's tab tags are cleared");
        int ownerBefore = owner.connection.messages.size();
        check(!publicChat(alice, "meant for the group"), "Message for a lost focus group is not leaked publicly");
        check(owner.connection.messages.size() == ownerBefore, "Former group does not receive it either");
        check(publicChat(alice, "now public"), "Focus cleared after losing membership");
        fail(d, owner.source, "gc create Focus");
        // Review fixes: no tab traffic per chat message, team formatting kept, no reply action onto subcommands.
        long infoBefore = alice.connection.packets.stream().filter(p -> p instanceof net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket).count();
        ok(d, owner.source, "gc WithoutLogs no tab refresh for chat");
        check(alice.connection.packets.stream().filter(p -> p instanceof net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket).count() == infoBefore, "Chat messages do not resend the tab list");
        var team = server.getScoreboard().addPlayerTeam("gcsmoke");
        team.setPlayerPrefix(Component.literal("<T> "));
        server.getScoreboard().addPlayerToTeam("GcOwner", team);
        ok(d, owner.source, "gc invite WithoutLogs GcOutside"); ok(d, outside.source, "gc accept WithoutLogs");
        check("[WithoutLogs] <T> GcOwner".equals(outside.connection.tabName(ownerId)), "Tag keeps scoreboard team prefix: " + outside.connection.tabName(ownerId));
        var reserved = de.groupchat.fabric.ChatText.message("Mute", new de.groupchat.core.GroupService.PreferenceView(null, "white", false), "x", "y");
        check(spans(reserved).stream().noneMatch(s -> s.style.getClickEvent() != null), "No reply action for a group named like a subcommand");
        var aliased = de.groupchat.fabric.ChatText.message("Mute", new de.groupchat.core.GroupService.PreferenceView("m", "white", false), "x", "y");
        check(spans(aliased).stream().anyMatch(s -> new ClickEvent.SuggestCommand("/gc m ").equals(s.style.getClickEvent())), "Alias reply still offered for such a group");
    }
    static void run(MinecraftServer server) throws Exception {
        var mod = FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class).stream()
                .filter(m -> m instanceof GroupChatMod).map(m -> (GroupChatMod)m).findFirst().orElseThrow();
        var service = mod.service();
        var d = server.getCommands().getDispatcher();
        var owner = player(server, "GcOwner"); var alice = player(server, "GcAlice");
        var bob = player(server, "GcBob"); var outside = player(server, "GcOutside");
        var console = server.createCommandSourceStack();
        Path configuration = FabricLoader.getInstance().getConfigDir().resolve("groupchats/groupchat.toml");
        Path logs = configuration.getParent().resolve("logs");
        check(Files.exists(configuration), "Configuration in new groupchats directory");
        if (de.groupchat.core.GcConfig.load(configuration).chatLogRetentionHours() == -1) {
            check(Files.readString(configuration).contains("chat_log_retention_hours = -1"), "Logging is disabled by default");
            check(!Files.exists(logs), "Disabled logging creates no log directory on startup");
            check(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().equals("groupchat-log-cleanup")), "Disabled logging starts no cleanup thread");
            ok(d, owner.source, "gc create WithoutLogs");
            ok(d, owner.source, "gc invite WithoutLogs GcAlice");
            ok(d, alice.source, "gc accept WithoutLogs");
            ok(d, alice.source, "gc shorten WithoutLogs w");
            ok(d, alice.source, "gc color WithoutLogs green");
            int before = outside.connection.messages.size();
            ok(d, owner.source, "gc WithoutLogs Message without logging");
            check(alice.connection.messages.getLast().getString().equals("[WithoutLogs] [w] GcOwner: Message without logging"), "Chat still delivered with a personal alias while logging is disabled");
            check(outside.connection.messages.size() == before, "Disabled logging preserves private delivery");
            check(!Files.exists(logs), "Disabled logging writes no message files");
            Files.writeString(logs, "Logging disabled: this path is intentionally blocked");
            ok(d, alice.source, "groupchat w Chat still works");
            check(owner.connection.messages.getLast().getString().equals("[WithoutLogs] GcAlice: Chat still works"), "An unusable log path does not block disabled chat");
            check(Files.readString(logs).equals("Logging disabled: this path is intentionally blocked"), "Disabled logging leaves existing files unchanged");
            focusMuteTabSmoke(server, d, owner, alice, outside);
            return;
        }
        check(Files.readString(configuration).contains("chat_log_retention_hours = 24"), "Explicit log retention setting loaded");
        check(Files.isDirectory(logs), "Log directory created");
        ok(d, owner.source, "gc create Base1");
        var gcNode = d.getRoot().getChild("gc");
        var inviteNode = gcNode.getChild("invite");
        var playerNode = (com.mojang.brigadier.tree.ArgumentCommandNode<CommandSourceStack, ?>) inviteNode.getChild("group").getChild("player");
        var missingGroup = new com.mojang.brigadier.context.CommandContextBuilder<>(d, owner.source, d.getRoot(), 0)
                .withNode(gcNode, com.mojang.brigadier.context.StringRange.between(0, 2))
                .withNode(inviteNode, com.mojang.brigadier.context.StringRange.between(3, 9))
                .build("gc invite ");
        check(playerNode.getCustomSuggestions().getSuggestions(missingGroup,
                new com.mojang.brigadier.suggestion.SuggestionsBuilder("gc invite ", 10)).get().isEmpty(),
                "Player completion handles a command context without a group argument");
        check(suggestions(d, owner.source, "gc invite Base1 ").contains("GcAlice"),
                "Player completion still suggests eligible players for a valid group");
        check(owner.connection.messages.getLast().getString().equals("[GC] Created group Base1."), "English success message");
        Path groupLog = logs.resolve(service.find("Base1").id() + ".jsonl");
        ok(d, owner.source, "groupchat create Base2");
        fail(d, alice.source, "gc create base1"); fail(d, owner.source, "gc create admin");
        ok(d, owner.source, "gc invite Base1 GcAlice");
        Component invitation = alice.connection.messages.getLast();
        check(invitation.getString().equals("[GC] GcOwner invited you to Base1. [Accept] [Decline]"), "English invitation and button labels");
        var clicks = spans(invitation).stream().map(s -> s.style.getClickEvent()).filter(Objects::nonNull).toList();
        check(clicks.contains(new ClickEvent.RunCommand("/gc accept Base1")), "Accept button command");
        check(clicks.contains(new ClickEvent.RunCommand("/gc decline Base1")), "Decline button command");
        fail(d, owner.source, "gc invite Base2 GcAlice");
        ok(d, alice.source, "groupchat accept base1");
        ok(d, owner.source, "gc invite Base1 GcBob"); ok(d, bob.source, "gc accept Base1");
        ok(d, owner.source, "gc coowner add Base1 GcAlice");
        fail(d, alice.source, "gc coowner add Base1 GcBob");
        fail(d, alice.source, "gc delete Base1"); fail(d, alice.source, "gc transfer Base1 GcBob");
        fail(d, alice.source, "gc kick Base1 GcOwner");
        ok(d, owner.source, "gc Base1 Before the rename");
        ok(d, alice.source, "gc rename Base1 BaseRenamed");
        ok(d, owner.source, "gc shorten BaseRenamed b"); ok(d, owner.source, "gc color BaseRenamed green");
        ok(d, alice.source, "gc shorten BaseRenamed ba"); ok(d, alice.source, "gc color BaseRenamed blue");
        check(suggestions(d, owner.source, "gc color BaseRenamed ").size() == 16, "16 color suggestions");
        check(suggestions(d, owner.source, "gc ").contains("b"), "Alias suggested");
        check(suggestions(d, console, "gc ").contains("admin"), "Console root completion handles a non-player source");
        check(!suggestions(d, outside.source, "gc ").contains("BaseRenamed"), "No group leaked in suggestions");
        check(!d.getRoot().getChild("gc").getChild("admin").canUse(alice.source), "Admin node excluded from ordinary player command tree");
        check(d.getRoot().getChild("gc").getChild("admin").canUse(console), "Admin node available for console");
        int before = outside.connection.messages.size();
        ok(d, owner.source, "groupchat b Hello everyone!");
        Component ownMessage = owner.connection.messages.getLast(), aliceMessage = alice.connection.messages.getLast(), bobMessage = bob.connection.messages.getLast();
        check(ownMessage.getString().equals("[BaseRenamed] [b] GcOwner: Hello everyone!"), "Owner alias and sender");
        check(aliceMessage.getString().equals("[BaseRenamed] [ba] GcOwner: Hello everyone!"), "Recipient alias");
        check(bobMessage.getString().equals("[BaseRenamed] GcOwner: Hello everyone!"), "No empty alias brackets");
        check(outside.connection.messages.size() == before, "Outsider receives no group message");
        for (var span : spans(ownMessage)) if (!span.text.isEmpty()) {
            check(span.style.getColor().getValue() == 0x55FF55, "Whole message green");
            check(span.style.isBold() == span.text.equals("BaseRenamed"), "Only group name bold");
        }
        for (var span : spans(aliceMessage)) if (!span.text.isEmpty()) check(span.style.getColor().getValue() == 0x5555FF, "Personal blue");
        var entries = Files.readAllLines(groupLog);
        check(entries.size() == 2, "Log once per message, not once per recipient; stable file across rename");
        check(JsonParser.parseString(entries.getFirst()).getAsJsonObject().get("group_name").getAsString().equals("Base1"), "Original group name retained");
        var lastEntry = JsonParser.parseString(entries.getLast()).getAsJsonObject();
        check(lastEntry.get("group_name").getAsString().equals("BaseRenamed"), "Renamed group recorded");
        check(lastEntry.get("sender_uuid").getAsString().equals(owner.player.getUUID().toString()), "Sender UUID in moderation log");
        check(lastEntry.get("sender_name").getAsString().equals("GcOwner"), "Sender name in moderation log");
        check(lastEntry.get("message").getAsString().equals("Hello everyone!"), "Unformatted message in moderation log");
        ok(d, owner.source, "gc Base2 Separate message");
        Path secondLog = logs.resolve(service.find("Base2").id() + ".jsonl");
        check(Files.readAllLines(secondLog).size() == 1, "Separate file for second group");
        check(Files.readAllLines(groupLog).size() == 2, "No cross-group log mixing");
        fail(d, outside.source, "gc BaseRenamed hello");
        check(outside.connection.messages.getLast().getString().equals("[GC] You do not belong to a group with that name or alias."), "English command error");
        fail(d, outside.source, "gc admin list");
        check(Files.readAllLines(groupLog).size() == 2, "Rejected messages are not logged");
        Path blockedBackup = groupLog.resolveSibling(groupLog.getFileName() + ".test-backup");
        Files.move(groupLog, blockedBackup); Files.createDirectory(groupLog);
        int aliceBeforeFailure = alice.connection.messages.size();
        try {
            fail(d, owner.source, "gc b Will_not_be_sent");
            check(alice.connection.messages.size() == aliceBeforeFailure, "Failed log write prevents unlogged delivery");
        } finally { Files.delete(groupLog); Files.move(blockedBackup, groupLog); }
        fail(d, owner.source.withPermission(LevelBasedPermissionSet.ADMIN), "gc admin list");
        ok(d, owner.source.withPermission(LevelBasedPermissionSet.OWNER), "gc admin list");
        ok(d, console, "gc admin info BaseRenamed");
        ok(d, console, "gc admin transfer BaseRenamed GcBob");
        ok(d, console, "gc admin transfer BaseRenamed GcBob confirm");
        check(service.find("BaseRenamed").owner().equals(bob.player.getUUID()), "Admin transfer applied");
        check(!service.find("BaseRenamed").manages(owner.player.getUUID()), "Former owner now ordinary member");
        fail(d, console, "gc admin transfer BaseRenamed GcOutside");
        ok(d, bob.source, "gc coowner add BaseRenamed GcOwner");
        fail(d, alice.source, "gc kick BaseRenamed GcOwner");
        ok(d, bob.source, "gc coowner remove BaseRenamed GcOwner");
        ok(d, alice.source, "gc kick BaseRenamed GcOwner");
        fail(d, owner.source, "gc b hello");
        // Simulate an offline known player: invite, then fire the same JOIN event as a real connection.
        UUID offlineId = UUID.nameUUIDFromBytes("OfflinePlayer:GcOffline".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        service.rememberPlayers(Map.of(offlineId, "GcOffline"));
        ok(d, alice.source, "gc invite BaseRenamed GcOffline");
        var offline = player(server, "GcOffline");
        check(offline.connection.messages.getLast().getString().contains("[Accept]"), "Offline invitation delivered at login");
        ok(d, offline.source, "gc decline BaseRenamed");
        ok(d, console, "gc admin delete BaseRenamed");
        check(spans(owner.connection.messages.getLast()).stream().noneMatch(s -> s.style.getClickEvent() != null), "No destructive confirmation button");
        ok(d, console, "gc admin delete BaseRenamed confirm");
        check(Files.readAllLines(groupLog).size() == 2, "Deleting group does not erase moderation logs prematurely");
        ok(d, console, "gc admin delete Base2"); ok(d, console, "gc admin delete Base2 confirm");
        check(service.groups().isEmpty(), "Cleanup complete");
        ok(d, owner.source, "gc help"); ok(d, alice.source, "gc list"); ok(d, outside.source, "groupchat");
        // Exercise the real periodic cleanup, including a now-deleted group's log.
        String expiredTime = java.time.Instant.now().minus(java.time.Duration.ofHours(25)).toString();
        var expiredEntries = new ArrayList<String>();
        for (String entry : Files.readAllLines(groupLog)) {
            var json = JsonParser.parseString(entry).getAsJsonObject(); json.addProperty("timestamp", expiredTime); expiredEntries.add(json.toString());
        }
        Files.write(groupLog, expiredEntries);
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(70).toNanos();
        while (Files.exists(groupLog) && System.nanoTime() < deadline) Thread.sleep(500);
        check(!Files.exists(groupLog), "Scheduled cleanup removes expired logs without new chat activity");
        check(Files.exists(secondLog), "Scheduled cleanup retains recent messages of deleted groups");
        if (FabricLoader.getInstance().isModLoaded("luckperms")) LuckPermsSmoke.run(server, service);
        else DeferredPermissionsSmoke.run(server, service);
    }
}
