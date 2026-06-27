package dev.mappywall.client;

import dev.mappywall.core.MapWallSave;
import net.minecraft.client.Minecraft;

public final class MovementController {
    public void tick(Minecraft client, MapWallSave save) {
        if (client.player != null) {
            client.player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("message.mappywall.auto_disabled"),
                    true
            );
        }
    }
}
