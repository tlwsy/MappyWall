package dev.mappywall.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

public final class MappyWallKeyBindings {
    private MappyWallKeyBindings() {
    }

    public static void register(MappyWallRuntime runtime) {
        KeyMapping openConfig = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.mappywall.open_config",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                "key.category.mappywall.controls"
        ));
        KeyMapping pauseResume = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.mappywall.pause_resume",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_P,
                "key.category.mappywall.controls"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openConfig.consumeClick()) {
                runtime.openConfigScreen(Minecraft.getInstance());
            }
            while (pauseResume.consumeClick()) {
                runtime.togglePause(Minecraft.getInstance());
            }
        });
    }

    @SuppressWarnings("unused")
    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MappyWallClient.MOD_ID, path);
    }
}

