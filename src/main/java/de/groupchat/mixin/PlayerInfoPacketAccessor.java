package de.groupchat.mixin;

import java.util.List;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets GroupChat send per-viewer tab-list names; vanilla only builds entries from the player's global display name. */
@Mixin(ClientboundPlayerInfoUpdatePacket.class)
public interface PlayerInfoPacketAccessor {
    @Mutable @Accessor("entries")
    void groupchat$setEntries(List<ClientboundPlayerInfoUpdatePacket.Entry> entries);
}
