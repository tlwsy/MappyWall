package dev.mappywall.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;

final class NavigationConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path path;
    private AutoNavigationConfig aggressiveConfig;

    NavigationConfigStore() {
        this.path = FabricLoader.getInstance().getConfigDir().resolve("mappywall").resolve("navigation.json");
        this.aggressiveConfig = loadOrDefault();
    }

    AutoNavigationConfig aggressiveConfig() {
        return aggressiveConfig;
    }

    void updateBreaking(boolean enabled, AutoNavigationConfig.ListMode mode, Set<String> blockIds) throws IOException {
        AutoNavigationConfig current = aggressiveConfig;
        AutoNavigationConfig candidate = new AutoNavigationConfig(
                enabled,
                mode,
                blockIds,
                current.blockPlacingEnabled(),
                current.placeListMode(),
                current.placeBlocks(),
                current.eatingEnabled(),
                current.foodListMode(),
                current.foods(),
                current.eatAtFoodLevel()
        );
        save(candidate);
        aggressiveConfig = candidate;
    }

    void resetBreakingDefaults() throws IOException {
        AutoNavigationConfig defaults = AutoNavigationConfig.aggressiveDefaults();
        updateBreaking(defaults.blockBreakingEnabled(), defaults.breakListMode(), defaults.breakBlocks());
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
            AutoNavigationConfig loaded = GSON.fromJson(reader, AutoNavigationConfig.class);
            return loaded == null ? defaults : loaded;
        } catch (IOException | RuntimeException invalidConfig) {
            return defaults;
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
