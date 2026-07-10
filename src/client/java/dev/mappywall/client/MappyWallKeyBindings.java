package dev.mappywall.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public final class MappyWallKeyBindings {
    private MappyWallKeyBindings() {
    }

    public static void register(MappyWallRuntime runtime) {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MappyWallClient.MOD_ID, "controls"));
        KeyMapping openConfig = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.mappywall.open_config",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                category
        ));
        KeyMapping pauseResume = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.mappywall.pause_resume",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_U,
                category
        ));
        KeyMapping emergencyStop = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.mappywall.emergency_stop",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openConfig.consumeClick()) {
                runtime.openConfigScreen(Minecraft.getInstance());
            }
            while (pauseResume.consumeClick()) {
                runtime.togglePause(Minecraft.getInstance());
            }
            while (emergencyStop.consumeClick()) {
                runtime.emergencyStop(Minecraft.getInstance());
            }
        });
    }

    @SuppressWarnings("unused")
    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MappyWallClient.MOD_ID, path);
    }
}
