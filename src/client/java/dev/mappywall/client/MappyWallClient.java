package dev.mappywall.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;

public final class MappyWallClient implements ClientModInitializer {
    public static final String MOD_ID = "mappywall";

    private static final MappyWallRuntime RUNTIME = new MappyWallRuntime();

    @Override
    public void onInitializeClient() {
        MappyWallKeyBindings.register(RUNTIME);
        HudProgressRenderer.register(RUNTIME);
        WorldTargetRenderer.register(RUNTIME);
        ClientTickEvents.END_CLIENT_TICK.register(RUNTIME::tick);
    }

    public static boolean beforeUseItem(Player player, InteractionHand hand) {
        return RUNTIME.beforeUseItem(Minecraft.getInstance(), player, hand);
    }

    public static boolean hasPendingMapOpening(Player player) {
        return RUNTIME.hasPendingMapOpening(Minecraft.getInstance(), player);
    }

    public static void cancelUnsentMapOpening(Player player) {
        RUNTIME.cancelUnsentMapOpening(Minecraft.getInstance(), player);
    }

    public static boolean beforeContainerInput(
            Player player,
            int containerId,
            int slotId,
            int button,
            ContainerInput input
    ) {
        return RUNTIME.beforeContainerInput(Minecraft.getInstance(), player, containerId, slotId, button, input);
    }

    public static boolean hasPendingMapZoom(Player player) {
        return RUNTIME.hasPendingMapZoom(Minecraft.getInstance(), player);
    }

    public static void afterContainerInput(Player player) {
        RUNTIME.afterContainerInput(Minecraft.getInstance(), player);
    }
}
