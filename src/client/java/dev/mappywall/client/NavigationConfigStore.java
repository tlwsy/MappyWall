package dev.mappywall.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mappywall.core.WaterTransitPolicy;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;

final class NavigationConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path path;
    private AutoNavigationConfig aggressiveConfig;

    NavigationConfigStore() {
        this(FabricLoader.getInstance().getConfigDir().resolve("mappywall").resolve("navigation.json"));
    }

    NavigationConfigStore(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.aggressiveConfig = loadOrDefault();
    }

    AutoNavigationConfig aggressiveConfig() {
        return aggressiveConfig;
    }

    void updateBreaking(boolean enabled, AutoNavigationConfig.ListMode mode, Set<String> blockIds) throws IOException {
        updateNavigationSettings(enabled, mode, blockIds, aggressiveConfig.minimumBoatDistanceBlocks());
    }

    void updateNavigationSettings(
            boolean breakingEnabled,
            AutoNavigationConfig.ListMode mode,
            Set<String> blockIds,
            int minimumBoatDistanceBlocks
    ) throws IOException {
        AutoNavigationConfig current = aggressiveConfig;
        AutoNavigationConfig candidate = new AutoNavigationConfig(
                breakingEnabled,
                mode,
                blockIds,
                current.blockPlacingEnabled(),
                current.placeListMode(),
                current.placeBlocks(),
                current.eatingEnabled(),
                current.foodListMode(),
                current.foods(),
                current.eatAtFoodLevel(),
                minimumBoatDistanceBlocks
        );
        save(candidate);
        aggressiveConfig = candidate;
    }

    void resetBreakingDefaults() throws IOException {
        resetNavigationDefaults();
    }

    void resetNavigationDefaults() throws IOException {
        AutoNavigationConfig defaults = AutoNavigationConfig.aggressiveDefaults();
        save(defaults);
        aggressiveConfig = defaults;
    }

    private AutoNavigationConfig loadOrDefault() {
        AutoNavigationConfig defaults = AutoNavigationConfig.aggressiveDefaults();
        if (!Files.isRegularFile(path)) {
            try {
                save(defaults);
            } catch (IOException ignored) {
                // In-memory defaults remain usable when the config directory is read-only.
            }
            return defaults;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject object = JsonParser.parseReader(reader).getAsJsonObject();
            object.addProperty(
                    "minimumBoatDistanceBlocks",
                    normalizedBoatDistance(object.get("minimumBoatDistanceBlocks"))
            );
            AutoNavigationConfig loaded = GSON.fromJson(object, AutoNavigationConfig.class);
            return loaded == null ? defaults : loaded;
        } catch (IOException | RuntimeException invalidConfig) {
            return defaults;
        }
    }

    private int normalizedBoatDistance(JsonElement element) {
        int fallback = WaterTransitPolicy.DEFAULT_MINIMUM_BOAT_DISTANCE_BLOCKS;
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            BigDecimal decimal = new BigDecimal(element.getAsString());
            if (decimal.scale() > 0) {
                BigDecimal stripped = decimal.stripTrailingZeros();
                if (stripped.scale() > 0) {
                    return fallback;
                }
            }
            if (decimal.compareTo(BigDecimal.valueOf(WaterTransitPolicy.MINIMUM_BOAT_DISTANCE_BLOCKS)) < 0) {
                return WaterTransitPolicy.MINIMUM_BOAT_DISTANCE_BLOCKS;
            }
            if (decimal.compareTo(BigDecimal.valueOf(WaterTransitPolicy.MAXIMUM_BOAT_DISTANCE_BLOCKS)) > 0) {
                return WaterTransitPolicy.MAXIMUM_BOAT_DISTANCE_BLOCKS;
            }
            return decimal.intValueExact();
        } catch (ArithmeticException | NumberFormatException invalid) {
            return fallback;
        }
    }

    private void save(AutoNavigationConfig config) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".navigation-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(config, writer);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
