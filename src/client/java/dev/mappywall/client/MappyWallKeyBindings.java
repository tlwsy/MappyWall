package dev.mappywall.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public final class MappyWallKeyBindings {
    private MappyWallKeyBindings() {
    }

    public static void register(MappyWallRuntime runtime) {
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MappyWallClient.MOD_ID, "controls"));
        KeyBinding openConfig = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mappywall.open_config",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                category
        ));
        KeyBinding pauseResume = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mappywall.pause_resume",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_U,
                category
        ));
        KeyBinding emergencyStop = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mappywall.emergency_stop",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openConfig.wasPressed()) {
                runtime.openConfigScreen(MinecraftClient.getInstance());
            }
            while (pauseResume.wasPressed()) {
                runtime.togglePause(MinecraftClient.getInstance());
            }
            while (emergencyStop.wasPressed()) {
                runtime.emergencyStop(MinecraftClient.getInstance());
            }
        });
    }

    @SuppressWarnings("unused")
    private static Identifier id(String path) {
        return Identifier.of(MappyWallClient.MOD_ID, path);
    }
}
