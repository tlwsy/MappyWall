package dev.mappywall.client;

import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public final class HudProgressRenderer {
    private HudProgressRenderer() {
    }

    public static void register(MappyWallRuntime runtime) {
        Identifier id = Identifier.of(MappyWallClient.MOD_ID, "progress");
        HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, id, (graphics, tickCounter) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) {
                return;
            }
            render(graphics, runtime.hudLines(client));
        });
    }

    private static void render(DrawContext graphics, List<Text> lines) {
        if (lines.isEmpty()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        int y = 8;
        for (Text line : lines) {
            graphics.drawText(client.textRenderer, line, 8, y, 0xFFFFFFFF, true);
            y += 10;
        }
    }
}
