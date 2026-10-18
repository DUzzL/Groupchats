package de.groupchat.fabric;

import de.groupchat.core.GroupService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import java.util.Locale;

public final class ChatText {
    private ChatText() {}
    public static MutableComponent message(String group, GroupService.PreferenceView preference, String sender, String message) {
        var color = ChatFormatting.valueOf(preference.color().toUpperCase(Locale.ROOT));
        String target = preference.alias() != null ? preference.alias() : group;
        // A legacy group named like a subcommand (e.g. "mute") can only be reached through an alias.
        boolean canReply = !GroupService.RESERVED.contains(GroupService.key(target));
        MutableComponent result = Component.empty().withStyle(style -> {
            var base = style.withColor(color).withBold(false);
            return canReply ? base.withClickEvent(new ClickEvent.SuggestCommand("/gc " + target + " "))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to reply in " + group)))
                    : base.withHoverEvent(new HoverEvent.ShowText(Component.literal("Set an alias with /gc shorten " + group + " <alias> to reply")));
        });
        result.append(Component.literal("[")).append(Component.literal(group).withStyle(style -> style.withBold(true))).append("] ");
        if (preference.alias() != null) result.append("[" + preference.alias() + "] ");
        return result.append(sender + ": " + message);
    }
    /** Tab-list prefix such as "[b][Team] ", in the viewer's colors; null when no group is shared. */
    public static MutableComponent tabTag(java.util.List<GroupService.GroupView> viewerGroups, java.util.function.Function<GroupService.GroupView, GroupService.PreferenceView> viewerPref, java.util.UUID target) {
        MutableComponent tag = null;
        for (var g : viewerGroups) {
            if (!g.members().contains(target)) continue;
            var pref = viewerPref.apply(g);
            if (tag == null) tag = Component.empty();
            var color = ChatFormatting.valueOf(pref.color().toUpperCase(Locale.ROOT));
            tag.append(Component.literal("[" + (pref.alias() != null ? pref.alias() : g.name()) + "]").withStyle(color));
        }
        return tag == null ? null : tag.append(" ");
    }
    public static MutableComponent invitation(GroupService.InvitationView invitation, String inviter) {
        String group = invitation.group().name();
        return Component.literal("[GC] " + inviter + " invited you to " + group + ". ").withStyle(ChatFormatting.YELLOW)
                .append(button("[Accept]", "/gc accept " + group, ChatFormatting.GREEN))
                .append(" ").append(button("[Decline]", "/gc decline " + group, ChatFormatting.RED));
    }
    private static Component button(String label, String command, ChatFormatting color) {
        return Component.literal(label).withStyle(style -> style.withColor(color).withBold(false)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
    }
}
