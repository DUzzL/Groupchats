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
        MutableComponent result = Component.empty().withStyle(style -> style.withColor(color).withBold(false));
        result.append(Component.literal("[")).append(Component.literal(group).withStyle(style -> style.withBold(true))).append("] ");
        if (preference.alias() != null) result.append("[" + preference.alias() + "] ");
        return result.append(sender + ": " + message);
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
