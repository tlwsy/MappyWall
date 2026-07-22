package dev.mappywall.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NavigationSettingsInputTest {
    @Test
    void acceptsOnlyTrimmedIntegersFromOneThroughOneHundredTwentyEight() {
        assertEquals(12, NavigationSettingsInput.parseMinimumBoatDistance(" 12 ").orElseThrow());
        assertEquals(1, NavigationSettingsInput.parseMinimumBoatDistance("1").orElseThrow());
        assertEquals(128, NavigationSettingsInput.parseMinimumBoatDistance("128").orElseThrow());
        assertTrue(NavigationSettingsInput.parseMinimumBoatDistance(null).isEmpty());
        assertTrue(NavigationSettingsInput.parseMinimumBoatDistance("").isEmpty());
        assertTrue(NavigationSettingsInput.parseMinimumBoatDistance("   ").isEmpty());
        assertTrue(NavigationSettingsInput.parseMinimumBoatDistance("0").isEmpty());
        assertTrue(NavigationSettingsInput.parseMinimumBoatDistance("129").isEmpty());
        assertTrue(NavigationSettingsInput.parseMinimumBoatDistance("12.5").isEmpty());
        assertTrue(NavigationSettingsInput.parseMinimumBoatDistance("boat").isEmpty());
    }

    @Test
    void validatedFieldFlowsThroughRuntimeStoreAndImmediateControllerRefresh()
            throws IOException {
        String screen = Files.readString(Path.of(
                "src", "client", "java", "dev", "mappywall", "client",
                "NavigationSettingsScreen.java"));
        String runtime = Files.readString(Path.of(
                "src", "client", "java", "dev", "mappywall", "client",
                "MappyWallRuntime.java"));
        String compactScreen = screen.replaceAll("\\s+", "");
        String compactRuntime = runtime.replaceAll("\\s+", "");
        assertTrue(compactScreen.contains("NavigationSettingsInput.parseMinimumBoatDistance("));
        assertTrue(compactScreen.contains(
                "runtime.updateAggressiveNavigationConfig("
                        + "breakingEnabled,listMode,parsed,parsedDistance.getAsInt())"));
        assertTrue(compactScreen.contains("minimumBoatDistanceBlocks=parsedDistance.getAsInt()"));
        assertFalse(compactScreen.contains(".setFilter("));
        assertTrue(compactRuntime.contains(
                "navigationConfigStore.updateNavigationSettings("
                        + "enabled,listMode,blockIds,minimumBoatDistanceBlocks)"));
        assertTrue(compactRuntime.contains(
                "movementController.setAggressiveConfig(navigationConfigStore.aggressiveConfig())"));
        assertTrue(compactRuntime.contains("navigationConfigStore.resetNavigationDefaults()"));
    }
}
