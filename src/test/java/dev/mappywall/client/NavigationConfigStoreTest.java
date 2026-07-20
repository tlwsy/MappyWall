package dev.mappywall.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NavigationConfigStoreTest {
    @TempDir
    Path directory;

    private int fixtureIndex;

    @Test
    void legacyConfigKeepsEveryOldFieldAndAddsTwelve() throws IOException {
        AutoNavigationConfig loaded = new NavigationConfigStore(writeConfig(null)).aggressiveConfig();

        assertLegacyFields(loaded);
        assertEquals(12, loaded.minimumBoatDistanceBlocks());
    }

    @Test
    void malformedBoatDistanceUsesTwelveWithoutDiscardingOtherFields() throws IOException {
        AutoNavigationConfig loaded = new NavigationConfigStore(writeConfig(
                "\"minimumBoatDistanceBlocks\": \"bad\""
        )).aggressiveConfig();

        assertLegacyFields(loaded);
        assertEquals(12, loaded.minimumBoatDistanceBlocks());
    }

    @Test
    void numericBoatDistanceIsClampedToTheSupportedRange() throws IOException {
        assertEquals(1, loadDistance("0"));
        assertEquals(1, loadDistance("-7"));
        assertEquals(128, loadDistance("129"));
        assertEquals(12, loadDistance("12.5"));
    }

    @Test
    void customDistanceRoundTripsAndResetRestoresTwelve() throws IOException {
        Path path = directory.resolve("navigation.json");
        NavigationConfigStore store = new NavigationConfigStore(path);
        AutoNavigationConfig current = store.aggressiveConfig();

        store.updateNavigationSettings(
                current.blockBreakingEnabled(),
                current.breakListMode(),
                current.breakBlocks(),
                37
        );
        assertEquals(37, new NavigationConfigStore(path).aggressiveConfig()
                .minimumBoatDistanceBlocks());

        store.resetNavigationDefaults();

        assertEquals(AutoNavigationConfig.aggressiveDefaults(), store.aggressiveConfig());
    }

    @Test
    void updatingVisibleSettingsPreservesEveryHiddenField() throws IOException {
        Path path = writeConfig("\"minimumBoatDistanceBlocks\": 37");
        NavigationConfigStore store = new NavigationConfigStore(path);

        store.updateNavigationSettings(
                false,
                AutoNavigationConfig.ListMode.WHITELIST,
                Set.of("minecraft:stone"),
                41
        );

        AutoNavigationConfig loaded = new NavigationConfigStore(path).aggressiveConfig();
        assertFalse(loaded.blockBreakingEnabled());
        assertEquals(AutoNavigationConfig.ListMode.WHITELIST, loaded.breakListMode());
        assertEquals(Set.of("minecraft:stone"), loaded.breakBlocks());
        assertTrue(loaded.blockPlacingEnabled());
        assertEquals(AutoNavigationConfig.ListMode.WHITELIST, loaded.placeListMode());
        assertEquals(Set.of("minecraft:cobblestone"), loaded.placeBlocks());
        assertFalse(loaded.eatingEnabled());
        assertEquals(AutoNavigationConfig.ListMode.WHITELIST, loaded.foodListMode());
        assertEquals(Set.of("minecraft:bread"), loaded.foods());
        assertEquals(7, loaded.eatAtFoodLevel());
        assertEquals(41, loaded.minimumBoatDistanceBlocks());
    }

    private int loadDistance(String rawJsonNumber) throws IOException {
        return new NavigationConfigStore(writeConfig(
                "\"minimumBoatDistanceBlocks\": " + rawJsonNumber
        )).aggressiveConfig().minimumBoatDistanceBlocks();
    }

    private Path writeConfig(String extraProperty) throws IOException {
        Path path = directory.resolve("legacy-" + fixtureIndex++ + ".json");
        String suffix = extraProperty == null ? "" : ",\n  " + extraProperty;
        Files.writeString(path, ("""
                {
                  "blockBreakingEnabled": true,
                  "breakListMode": "BLACKLIST",
                  "breakBlocks": ["minecraft:dirt"],
                  "blockPlacingEnabled": true,
                  "placeListMode": "WHITELIST",
                  "placeBlocks": ["minecraft:cobblestone"],
                  "eatingEnabled": false,
                  "foodListMode": "WHITELIST",
                  "foods": ["minecraft:bread"],
                  "eatAtFoodLevel": 7%s
                }
                """).formatted(suffix));
        return path;
    }

    private static void assertLegacyFields(AutoNavigationConfig loaded) {
        assertTrue(loaded.blockBreakingEnabled());
        assertEquals(AutoNavigationConfig.ListMode.BLACKLIST, loaded.breakListMode());
        assertEquals(Set.of("minecraft:dirt"), loaded.breakBlocks());
        assertTrue(loaded.blockPlacingEnabled());
        assertEquals(AutoNavigationConfig.ListMode.WHITELIST, loaded.placeListMode());
        assertEquals(Set.of("minecraft:cobblestone"), loaded.placeBlocks());
        assertFalse(loaded.eatingEnabled());
        assertEquals(AutoNavigationConfig.ListMode.WHITELIST, loaded.foodListMode());
        assertEquals(Set.of("minecraft:bread"), loaded.foods());
        assertEquals(7, loaded.eatAtFoodLevel());
    }
}
