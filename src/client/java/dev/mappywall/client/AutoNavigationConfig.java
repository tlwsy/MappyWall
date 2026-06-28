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
                true,
                ListMode.BLACKLIST,
                Set.of(
                        "minecraft:bedrock",
                        "minecraft:barrier",
                        "minecraft:command_block",
                        "minecraft:chain_command_block",
                        "minecraft:repeating_command_block",
                        "minecraft:structure_block",
                        "minecraft:jigsaw",
                        "minecraft:spawner",
                        "minecraft:chest",
                        "minecraft:trapped_chest",
                        "minecraft:barrel",
                        "minecraft:shulker_box",
                        "minecraft:ender_chest",
                        "minecraft:end_portal_frame"
                ),
                true,
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
                        "minecraft:golden_apple",
                        "minecraft:enchanted_golden_apple"
                ),
                16
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
