package de.groupchat.fabric;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import de.groupchat.core.GcException;
import de.groupchat.core.GroupService;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.concurrent.*;
import java.util.*;
import static net.minecraft.commands.Commands.*;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;

public final class GcCommands {
    private final GroupChatMod mod;
    public GcCommands(GroupChatMod mod) { this.mod = mod; }
    private GroupService service() { return mod.service(); }
    private final Map<UUID, UUID> pendingLimits = new HashMap<>();
    private enum Scope { MEMBER, MANAGER, OWNER, INVITATION, ADMIN }
    @FunctionalInterface private interface Action { void run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException; }
    private Command<CommandSourceStack> command(Action action) {
        return context -> {
            try {
                String permission = commandAction(context);
                if (!(permission.equals("admin") ? admin(context.getSource()) : GroupPermissions.allowed(context.getSource(), permission)))
                    throw new GcException("You do not have permission to use this command.");
                action.run(context);
                return 1;
            }
            catch (GcException e) { context.getSource().sendFailure(Component.literal("[GC] " + e.getMessage())); return 0; }
            catch (java.io.UncheckedIOException e) {
                GroupChatMod.LOGGER.error("GroupChat could not save a change", e);
                context.getSource().sendFailure(Component.literal("[GC] Storage error. The change has been rolled back; please contact an administrator."));
                return 0;
            }
        };
    }
    private static String arg(CommandContext<CommandSourceStack> c, String name) { return getString(c, name); }
    private static UUID actor(CommandContext<CommandSourceStack> c) throws CommandSyntaxException { return c.getSource().getPlayerOrException().getUUID(); }
    private static UUID adminActor(CommandContext<CommandSourceStack> c) { return c.getSource().isPlayer() ? c.getSource().getPlayer().getUUID() : null; }
    private static void reply(CommandContext<CommandSourceStack> c, String text) {
        c.getSource().sendSuccess(() -> Component.literal("[GC] " + text).withStyle(ChatFormatting.YELLOW), false);
    }
    private static boolean admin(CommandSourceStack source) { return GroupPermissions.anyAdmin(source); }
    private static String commandAction(CommandContext<CommandSourceStack> context) {
        var names = context.getNodes().stream().map(n -> n.getNode()).filter(n -> n instanceof LiteralCommandNode<?>)
                .map(n -> n.getName()).filter(n -> !n.equals("gc") && !n.equals("groupchat")).toList();
        if (names.isEmpty()) return context.getNodes().stream().anyMatch(n -> n.getNode().getName().equals("chat")) ? "chat" : "help";
        String first = names.getFirst();
        if ((first.equals("admin") || first.equals("coowner")) && names.size() > 1) return first + "." + names.get(1);
        return first;
    }
    private static LiteralArgumentBuilder<CommandSourceStack> restricted(String name) { return restricted(name, name); }
    private static LiteralArgumentBuilder<CommandSourceStack> restricted(String name, String action) {
        return literal(name).requires(source -> GroupPermissions.allowed(source, action));
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = literal("gc").executes(command(this::help));
        root.then(restricted("help").executes(command(this::help)));
        root.then(restricted("list").executes(command(this::listGroups)));
        root.then(restricted("info").then(group("group", Scope.MEMBER).executes(command(c -> info(c, false)))));
        root.then(restricted("create").then(word("name").executes(command(this::create))));
        root.then(restricted("rename").then(group("group", Scope.MANAGER).then(word("newName").executes(command(c -> {
            var updated = service().rename(actor(c), arg(c, "group"), arg(c, "newName"));
            broadcast(c, updated, "The group is now called " + updated.name() + ".");
            // Refresh buttons whose commands still contain the old group name.
            for (var player : c.getSource().getServer().getPlayerList().getPlayers())
                service().invitations(player.getUUID()).stream().filter(i -> i.group().id().equals(updated.id()))
                        .forEach(i -> player.sendSystemMessage(ChatText.invitation(i, service().playerName(i.inviter()))));
        })))));
        root.then(restricted("invite").then(group("group", Scope.MANAGER).then(player("player", "invite").executes(command(c -> {
            UUID target = service().playerId(arg(c, "player"));
            var invitation = service().invite(actor(c), arg(c, "group"), target);
            var recipient = c.getSource().getServer().getPlayerList().getPlayer(target);
            if (recipient != null) recipient.sendSystemMessage(ChatText.invitation(invitation, service().playerName(actor(c))));
            reply(c, "Invitation sent to " + service().playerName(target) + (recipient == null ? " (will be shown at their next login)." : "."));
        })))));
        root.then(restricted("kick").then(group("group", Scope.MANAGER).then(player("player", "kick").executes(command(c -> {
            UUID target = service().playerId(arg(c, "player"));
            var updated = service().kick(actor(c), arg(c, "group"), target);
            broadcast(c, updated, service().playerName(target) + " was removed from " + updated.name() + ".");
            tell(c, target, "You were removed from " + updated.name() + ".");
        })))));
        for (boolean accept : new boolean[]{true, false}) {
            root.then(restricted(accept ? "accept" : "decline").then(group("group", Scope.INVITATION).executes(command(c -> {
                var updated = service().respond(actor(c), arg(c, "group"), accept);
                if (accept) broadcast(c, updated, service().playerName(actor(c)) + " joined " + updated.name() + ".");
                else reply(c, "Declined the invitation to " + updated.name() + ".");
            }))));
        }
        root.then(restricted("leave").then(group("group", Scope.MEMBER).executes(command(c -> {
            var updated = service().leave(actor(c), arg(c, "group"));
            reply(c, "You left " + updated.name() + ".");
            broadcast(c, updated, service().playerName(actor(c)) + " left " + updated.name() + ".");
        }))));
        root.then(restricted("shorten").then(group("group", Scope.MEMBER)
                .executes(command(c -> { service().shorten(actor(c), arg(c, "group"), null); reply(c, "Personal alias removed."); }))
                .then(word("alias").executes(command(c -> {
                    service().shorten(actor(c), arg(c, "group"), arg(c, "alias")); reply(c, "Your alias is now " + arg(c, "alias") + ".");
                })))));
        root.then(restricted("color").then(group("group", Scope.MEMBER).then(word("color")
                .suggests((c, b) -> SharedSuggestionProvider.suggest(GroupService.COLORS, b))
                .executes(command(c -> {
                    service().color(actor(c), arg(c, "group"), arg(c, "color")); reply(c, "Your group color has been updated.");
                })))));
        var coowner = literal("coowner").requires(source -> GroupPermissions.allowed(source, "coowner.add") || GroupPermissions.allowed(source, "coowner.remove"));
        for (boolean add : new boolean[]{true, false}) {
            coowner.then(restricted(add ? "add" : "remove", add ? "coowner.add" : "coowner.remove").then(group("group", Scope.OWNER).then(player("player", add ? "add" : "remove")
                    .executes(command(c -> {
                        UUID target = service().playerId(arg(c, "player"));
                        var updated = service().coowner(actor(c), arg(c, "group"), target, add);
                        broadcast(c, updated, service().playerName(target) + (add ? " is now a co-owner." : " is no longer a co-owner."));
                    })))));
        }
        root.then(coowner);
        root.then(deleteNode(false)); root.then(transferNode(false));
        root.then(literal("admin").requires(GcCommands::admin)
                .executes(command(c -> reply(c, "/gc admin list | info <group> | delete <group> [confirm] | transfer <group> <player> [confirm]")))
                .then(restricted("list", "admin.list").executes(command(c -> {
                    reply(c, "All groups (" + service().groups().size() + "):");
                    for (var g : service().groups()) reply(c, g.name() + " · Owner: " + service().playerName(g.owner()) + " · Members: " + g.members().size());
                })))
                .then(restricted("info", "admin.info").then(group("group", Scope.ADMIN).executes(command(c -> info(c, true)))))
                .then(deleteNode(true)).then(transferNode(true)));
        root.then(word("chat").requires(source -> source.isPlayer() && GroupPermissions.allowed(source, "chat"))
                .suggests((c, b) -> {
                    if (!c.getSource().isPlayer() || !GroupPermissions.allowed(c.getSource(), "chat")) return b.buildFuture();
                    var names = new ArrayList<String>();
                    for (var g : service().groupsFor(c.getSource().getPlayer().getUUID())) {
                        names.add(g.name());
                        String alias = service().preference(c.getSource().getPlayer().getUUID(), g.id()).alias();
                        if (alias != null) names.add(alias);
                    }
                    return SharedSuggestionProvider.suggest(names, b);
                })
                .then(argument("message", StringArgumentType.greedyString()).executes(command(this::chat))));
        var registered = dispatcher.register(root);
        dispatcher.register(literal("groupchat").executes(command(this::help)).redirect(registered));
    }
    private LiteralArgumentBuilder<CommandSourceStack> deleteNode(boolean isAdmin) {
        return restricted("delete", isAdmin ? "admin.delete" : "delete").then(group("group", isAdmin ? Scope.ADMIN : Scope.OWNER)
                .executes(command(c -> delete(c, isAdmin, false)))
                .then(literal("confirm").executes(command(c -> delete(c, isAdmin, true)))));
    }
    private LiteralArgumentBuilder<CommandSourceStack> transferNode(boolean isAdmin) {
        return restricted("transfer", isAdmin ? "admin.transfer" : "transfer").then(group("group", isAdmin ? Scope.ADMIN : Scope.OWNER).then(player("player", "transfer")
                .executes(command(c -> transfer(c, isAdmin, false)))
                .then(literal("confirm").executes(command(c -> transfer(c, isAdmin, true))))));
    }
    private void delete(CommandContext<CommandSourceStack> c, boolean isAdmin, boolean confirm) throws CommandSyntaxException {
        if (isAdmin && !GroupPermissions.allowed(c.getSource(), "admin.delete")) throw new GcException("You do not have permission to delete groups as an administrator.");
        var group = service().find(arg(c, "group"));
        UUID actor = isAdmin ? adminActor(c) : actor(c);
        if (service().delete(actor, group.name(), isAdmin, confirm)) {
            broadcast(c, group, "Group " + group.name() + " has been deleted.");
            if (actor == null || !group.members().contains(actor)) reply(c, "Deleted group " + group.name() + ".");
            GroupChatMod.LOGGER.info("{} deleted group {} ({}){}.", c.getSource().getTextName(), group.name(), group.id(), isAdmin ? " (Admin)" : "");
        } else reply(c, "To confirm deletion, run within 30 seconds: /gc " + (isAdmin ? "admin " : "") + "delete " + group.name() + " confirm");
    }
    private void create(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        UUID player = actor(c);
        withOwnershipLimit(c, player, (context, limit) -> {
            var group = service().create(player, arg(context, "name"), limit);
            reply(context, "Created group " + group.name() + ".");
        });
    }
    @FunctionalInterface private interface LimitedAction { void run(CommandContext<CommandSourceStack> context, int limit) throws CommandSyntaxException; }
    private void withOwnershipLimit(CommandContext<CommandSourceStack> c, UUID target, LimitedAction action) throws CommandSyntaxException {
        UUID requester = adminActor(c);
        if (pendingLimits.containsKey(requester)) throw new GcException("A permission lookup is already in progress. Please wait.");
        CompletableFuture<Integer> future = GroupPermissions.ownershipLimit(c.getSource().getServer(), target, service().defaultOwnershipLimit());
        if (future.isDone()) {
            try { action.run(c, future.join()); }
            catch (CompletionException e) { throw lookupFailure(e); }
            return;
        }
        UUID request = UUID.randomUUID();
        pendingLimits.put(requester, request);
        reply(c, "Loading the player's permissions. Your command will continue automatically.");
        future.orTimeout(10, TimeUnit.SECONDS).whenComplete((limit, failure) -> c.getSource().getServer().execute(() -> {
            if (!request.equals(pendingLimits.remove(requester))) return;
            var source = c.getSource();
            if (source.isPlayer() && source.getServer().getPlayerList().getPlayer(requester) != source.getPlayer()) return;
            // Recheck current permissions after the asynchronous lookup, including OP changes.
            var current = source.isPlayer() ? c.copyFor(source.getPlayer().createCommandSourceStack()) : c;
            try {
                command(context -> {
                    if (failure != null) throw lookupFailure(failure);
                    action.run(context, limit);
                }).run(current);
            } catch (CommandSyntaxException e) { current.getSource().sendFailure(Component.literal(e.getMessage())); }
        }));
    }
    private GcException lookupFailure(Throwable failure) {
        GroupChatMod.LOGGER.error("Could not resolve the ownership limit; command was not applied.", failure);
        return new GcException("Could not load permissions. No changes were made; please try again.");
    }
    private void transfer(CommandContext<CommandSourceStack> c, boolean isAdmin, boolean confirm) throws CommandSyntaxException {
        UUID target = service().playerId(arg(c, "player"));
        String name = arg(c, "group");
        UUID expectedGroup = service().find(name).id();
        withOwnershipLimit(c, target, (context, limit) -> {
            if (!service().find(name).id().equals(expectedGroup)) throw new GcException("The group has changed. Please run the command again.");
            UUID actor = isAdmin ? adminActor(context) : actor(context);
            if (service().transfer(actor, name, target, isAdmin, confirm, limit)) {
                var group = service().find(name);
                broadcast(context, group, service().playerName(target) + " is now the owner of " + group.name() + ".");
                if (actor == null || !group.members().contains(actor)) reply(context, "Transferred group " + group.name() + ".");
                GroupChatMod.LOGGER.info("{} transferred group {} ({}) to {}{}.", context.getSource().getTextName(), group.name(), group.id(), target, isAdmin ? " (Admin)" : "");
            } else reply(context, "To confirm the transfer, run within 30 seconds: /gc " + (isAdmin ? "admin " : "")
                    + "transfer " + name + " " + service().playerName(target) + " confirm");
        });
    }
    private static RequiredArgumentBuilder<CommandSourceStack, String> word(String name) { return argument(name, StringArgumentType.word()); }
    private RequiredArgumentBuilder<CommandSourceStack, String> group(String name, Scope scope) {
        return word(name).suggests((c, b) -> {
            if (!GroupPermissions.allowed(c.getSource(), commandAction(c))) return b.buildFuture();
            UUID player = c.getSource().isPlayer() ? c.getSource().getPlayer().getUUID() : null;
            List<String> names;
            if (scope == Scope.ADMIN) names = admin(c.getSource()) ? service().groups().stream().map(GroupService.GroupView::name).toList() : List.of();
            else if (player == null) names = List.of();
            else if (scope == Scope.INVITATION) names = service().invitations(player).stream().map(i -> i.group().name()).toList();
            else names = service().groupsFor(player).stream()
                        .filter(g -> scope != Scope.MANAGER || g.manages(player))
                        .filter(g -> scope != Scope.OWNER || g.owner().equals(player)).map(GroupService.GroupView::name).toList();
            return SharedSuggestionProvider.suggest(names, b);
        });
    }
    private RequiredArgumentBuilder<CommandSourceStack, String> player(String name, String operation) {
        return word(name).suggests((c, b) -> {
            try {
                String action = commandAction(c);
                if (!GroupPermissions.allowed(c.getSource(), action)) return b.buildFuture();
                var g = service().find(arg(c, "group"));
                UUID actor = c.getSource().isPlayer() ? c.getSource().getPlayer().getUUID() : null;
                if (!action.equals("admin.transfer")) {
                    if (actor == null || !g.manages(actor)) return b.buildFuture();
                    if (!operation.equals("invite") && !operation.equals("kick") && !g.owner().equals(actor)) return b.buildFuture();
                }
                var names = service().knownPlayers().entrySet().stream().filter(e -> {
                    UUID target = e.getKey();
                    return switch (operation) {
                        case "invite" -> !g.members().contains(target);
                        case "add" -> g.members().contains(target) && !g.owner().equals(target) && !g.coowners().contains(target);
                        case "remove" -> g.coowners().contains(target);
                        case "kick" -> g.members().contains(target) && !g.owner().equals(target) && !target.equals(actor)
                                && (g.owner().equals(actor) || !g.coowners().contains(target));
                        default -> g.members().contains(target) && !g.owner().equals(target);
                    };
                }).map(Map.Entry::getValue).sorted(String.CASE_INSENSITIVE_ORDER).toList();
                return SharedSuggestionProvider.suggest(names, b);
            } catch (GcException e) { return b.buildFuture(); }
        });
    }
    private void chat(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        UUID sender = actor(c);
        if (GroupService.RESERVED.contains(GroupService.key(arg(c, "chat")))) throw new GcException("You do not have permission to use this command.");
        var group = service().forChat(sender, arg(c, "chat"));
        String message = arg(c, "message").trim();
        if (message.isEmpty() || message.length() > 2048 || message.codePoints().anyMatch(ch -> Character.isISOControl(ch) || ch == '§'))
            throw new GcException("The message is empty, too long, or contains control characters.");
        String senderName = c.getSource().getPlayerOrException().getName().getString();
        mod.logMessage(group, sender, senderName, message);
        for (UUID member : group.members()) {
            var recipient = c.getSource().getServer().getPlayerList().getPlayer(member);
            if (recipient != null) recipient.sendSystemMessage(ChatText.message(group.name(), service().preference(member, group.id()),
                    senderName, message));
        }
    }
    private void listGroups(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        UUID player = actor(c);
        reply(c, "Your groups (" + service().groupsFor(player).size() + "):");
        for (var group : service().groupsFor(player)) {
            var pref = service().preference(player, group.id());
            reply(c, group.name() + (pref.alias() == null ? "" : " [" + pref.alias() + "]") + " · "
                    + (group.owner().equals(player) ? "Owner" : group.coowners().contains(player) ? "Co-Owner" : "Member") + " · " + pref.color());
        }
        for (var invite : service().invitations(player)) c.getSource().sendSystemMessage(ChatText.invitation(invite, service().playerName(invite.inviter())));
    }
    private void info(CommandContext<CommandSourceStack> c, boolean isAdmin) throws CommandSyntaxException {
        var g = service().find(arg(c, "group"));
        if (isAdmin) { if (!GroupPermissions.allowed(c.getSource(), "admin.info")) throw new GcException("You do not have permission to inspect groups as an administrator."); }
        else if (!g.members().contains(actor(c))) throw new GcException("You are not a member of this group.");
        reply(c, g.name() + " · Owner: " + service().playerName(g.owner()));
        reply(c, "Co-Owner: " + names(g.coowners()));
        reply(c, "Members (" + g.members().size() + "): " + names(g.members()));
    }
    private String names(Set<UUID> players) {
        return players.isEmpty() ? "–" : String.join(", ", players.stream().map(service()::playerName).sorted(String.CASE_INSENSITIVE_ORDER).toList());
    }
    private void broadcast(CommandContext<CommandSourceStack> c, GroupService.GroupView group, String message) {
        for (UUID member : group.members()) tell(c, member, message);
    }
    private void tell(CommandContext<CommandSourceStack> c, UUID player, String message) {
        var recipient = c.getSource().getServer().getPlayerList().getPlayer(player);
        if (recipient != null) recipient.sendSystemMessage(Component.literal("[GC] " + message).withStyle(ChatFormatting.YELLOW));
    }
    private void help(CommandContext<CommandSourceStack> c) {
        for (String line : List.of(
                "/gc <group|alias> <message> – Send a group message",
                "/gc create <name> | rename <group> <newName>",
                "/gc invite <group> <player> | kick <group> <player>",
                "/gc accept <group> | decline <group> | leave <group>",
                "/gc coowner add|remove <group> <player>",
                "/gc delete <group> [confirm] | transfer <group> <player> [confirm]",
                "/gc shorten <group> [alias] – Omit the alias to remove your personal alias",
                "/gc color <group> <color> – Press Tab to see all 16 colors",
                "/gc list | info <group> | help · Alias: /groupchat")) reply(c, line);
        if (admin(c.getSource())) reply(c, "/gc admin list | info <group> | delete <group> [confirm] | transfer <group> <player> [confirm]");
    }
}
