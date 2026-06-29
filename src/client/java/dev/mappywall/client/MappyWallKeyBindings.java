package dev.mappywall.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import org.lwjgl.glfw.GLFW;

public final class MappyWallKeyBindings {
    private MappyWallKeyBindings() {
    }

    public static void register(MappyWallRuntime runtime) {
        KeyBinding openConfig = KeyBindingHelper.registerKeyBinding(MinecraftCompat.createKeyBinding(
                "key.mappywall.open_config",
                GLFW.GLFW_KEY_M
        ));
        KeyBinding pauseResume = KeyBindingHelper.registerKeyBinding(MinecraftCompat.createKeyBinding(
                "key.mappywall.pause_resume",
                GLFW.GLFW_KEY_U
        ));
        KeyBinding emergencyStop = KeyBindingHelper.registerKeyBinding(MinecraftCompat.createKeyBinding(
                "key.mappywall.emergency_stop",
                GLFW.GLFW_KEY_K
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
}
