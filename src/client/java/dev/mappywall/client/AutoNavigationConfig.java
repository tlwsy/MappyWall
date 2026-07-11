package dev.mappywall.client;

import java.util.Set;

public record AutoNavigationConfig(
        boolean blockBreakingEnabled,
        ListMode breakListMode,
        Set<String> breakBlocks,
        boolean blockPlacingEnabled,
        ListMode placeListMode,
        Set<String> placeBlocks,
        boolean eatingEnabled,
        ListMode foodListMode,
        Set<String> foods,
        int eatAtFoodLevel
) {
    public static AutoNavigationConfig defaults() {
        return new AutoNavigationConfig(
                false,
                ListMode.WHITELIST,
                Set.of(
                        "minecraft:dirt",
                        "minecraft:grass_block",
                        "minecraft:stone",
                        "minecraft:cobblestone",
                        "minecraft:netherrack",
                        "minecraft:sand",
                        "minecraft:gravel"
                ),
                false,
                ListMode.WHITELIST,
                Set.of(
                        "minecraft:cobblestone",
                        "minecraft:dirt"
                ),
                true,
                ListMode.BLACKLIST,
                Set.of(
                        "minecraft:rotten_flesh",
                        "minecraft:spider_eye",
                        "minecraft:poisonous_potato",
                        "minecraft:pufferfish",
                        "minecraft:suspicious_stew",
                        "minecraft:chicken",
                        "minecraft:chorus_fruit",
                        "minecraft:golden_apple",
                        "minecraft:enchanted_golden_apple"
                ),
                16
        );
    }

    /**
     * Aggressive movement keeps bridging available, but still requires the strict placement
     * whitelist. Destructive pathing remains opt-in even in aggressive mode.
     */
    public static AutoNavigationConfig aggressiveDefaults() {
        AutoNavigationConfig safe = defaults();
        return new AutoNavigationConfig(
                false,
                safe.breakListMode,
                safe.breakBlocks,
                true,
                safe.placeListMode,
                safe.placeBlocks,
                safe.eatingEnabled,
                safe.foodListMode,
                safe.foods,
                safe.eatAtFoodLevel
        );
    }

    public boolean allowsBreak(String blockId) {
        return blockBreakingEnabled && breakListMode.allows(breakBlocks, blockId);
    }

    public boolean allowsPlace(String itemId) {
        return blockPlacingEnabled && placeListMode.allows(placeBlocks, itemId);
    }

    public boolean allowsFood(String itemId) {
        return eatingEnabled && foodListMode.allows(foods, itemId);
    }

    public enum ListMode {
        WHITELIST,
        BLACKLIST;

        boolean allows(Set<String> entries, String id) {
            return this == WHITELIST ? entries.contains(id) : !entries.contains(id);
        }
    }
}
