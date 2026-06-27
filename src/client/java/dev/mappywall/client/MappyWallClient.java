package dev.mappywall.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class MappyWallClient implements ClientModInitializer {
    public static final String MOD_ID = "mappywall";

    private static final MappyWallRuntime RUNTIME = new MappyWallRuntime();

    @Override
    public void onInitializeClient() {
        MappyWallKeyBindings.register(RUNTIME);
        HudProgressRenderer.register(RUNTIME);
        ClientTickEvents.END_CLIENT_TICK.register(RUNTIME::tick);
    }
}

