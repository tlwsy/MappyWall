package dev.mappywall.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class MinecraftCompatibilityContractTest {
    @Test
    void blockCenterPreservesHalfBlockOffsetsForNegativeCoordinates() {
        Vec3 center = MovementController.blockCenter(new BlockPos(-2, 63, 4));

        assertEquals(-1.5, center.x);
        assertEquals(63.5, center.y);
        assertEquals(4.5, center.z);
    }

    @Test
    void processedMetadataBoundsTheSharedClientRelease() throws IOException {
        try (InputStream input = MinecraftCompatibilityContractTest.class
                .getClassLoader()
                .getResourceAsStream("fabric.mod.json")) {
            assertNotNull(input);
            JsonObject metadata = JsonParser.parseReader(new InputStreamReader(
                    input, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject depends = metadata.getAsJsonObject("depends");

            assertEquals("0.1.33+mc26.1-26.1.2", metadata.get("version").getAsString());
            assertEquals(">=26.1 <=26.1.2", depends.get("minecraft").getAsString());
            assertEquals(">=0.19.3", depends.get("fabricloader").getAsString());
            assertEquals(">=25", depends.get("java").getAsString());
            assertEquals("client", metadata.get("environment").getAsString());
            assertTrue(metadata.getAsJsonObject("entrypoints").has("client"));
            assertFalse(metadata.getAsJsonObject("entrypoints").has("main"));
            assertFalse(metadata.getAsJsonObject("entrypoints").has("server"));
        }
    }
}
