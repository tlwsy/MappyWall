package dev.mappywall.client;

import dev.mappywall.core.MapWallSave;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

public final class MovementController {
    public void tick(MinecraftClient client, MapWallSave save) {
        if (client.player != null) {
            client.player.sendMessage(
                    Text.translatable("message.mappywall.auto_disabled"),
                    true
            );
        }
    }
}
