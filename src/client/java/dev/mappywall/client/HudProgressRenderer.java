package dev.mappywall.client;

import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public final class HudProgressRenderer {
    private HudProgressRenderer() {
    }

    public static void register(MappyWallRuntime runtime) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(MappyWallClient.MOD_ID, "progress");
        HudLayerRegistrationCallback.EVENT.register(registry -> registry.addLayer(new IdentifiedLayer(id, (graphics, tickCounter) -> {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) {
                return;
            }
            render(graphics, runtime.hudLines(client));
        })));
    }

    private static void render(GuiGraphics graphics, List<Component> lines) {
        if (lines.isEmpty()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        int y = 8;
        for (Component line : lines) {
            graphics.drawString(client.font, line, 8, y, 0xFFFFFFFF, true);
            y += 10;
        }
    }
}

