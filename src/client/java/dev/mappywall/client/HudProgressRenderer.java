package dev.mappywall.client;

import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class HudProgressRenderer {
    private HudProgressRenderer() {
    }

    public static void register(MappyWallRuntime runtime) {
        Identifier id = Identifier.fromNamespaceAndPath(MappyWallClient.MOD_ID, "progress");
        HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, id, (graphics, tickCounter) -> {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) {
                return;
            }
            render(graphics, runtime.hudLines(client));
        });
    }

    private static void render(GuiGraphicsExtractor graphics, List<Component> lines) {
        if (lines.isEmpty()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        int y = 8;
        for (Component line : lines) {
            graphics.text(client.font, line, 8, y, 0xFFFFFFFF, true);
            y += 10;
        }
    }
}
