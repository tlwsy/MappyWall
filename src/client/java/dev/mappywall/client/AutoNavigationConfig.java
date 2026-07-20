package dev.mappywall.client;

import dev.mappywall.core.WaterTransitPolicy;
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
        int eatAtFoodLevel,
        int minimumBoatDistanceBlocks
) {
    public AutoNavigationConfig {
        breakListMode = breakListMode == null ? ListMode.WHITELIST : breakListMode;
        breakBlocks = breakBlocks == null ? Set.of() : Set.copyOf(breakBlocks);
        placeListMode = placeListMode == null ? ListMode.WHITELIST : placeListMode;
        placeBlocks = placeBlocks == null ? Set.of() : Set.copyOf(placeBlocks);
        foodListMode = foodListMode == null ? ListMode.BLACKLIST : foodListMode;
        foods = foods == null ? Set.of() : Set.copyOf(foods);
        eatAtFoodLevel = Math.max(0, Math.min(20, eatAtFoodLevel));
        minimumBoatDistanceBlocks = Math.max(
                WaterTransitPolicy.MINIMUM_BOAT_DISTANCE_BLOCKS,
                Math.min(WaterTransitPolicy.MAXIMUM_BOAT_DISTANCE_BLOCKS, minimumBoatDistanceBlocks)
        );
    }

    public static final Set<String> DEFAULT_NATURAL_BREAK_BLOCKS = Set.of(
            "minecraft:stone",
            "minecraft:granite",
            "minecraft:diorite",
            "minecraft:andesite",
            "minecraft:deepslate",
            "minecraft:cobbled_deepslate",
            "minecraft:tuff",
            "minecraft:calcite",
            "minecraft:dripstone_block",
            "minecraft:dirt",
            "minecraft:coarse_dirt",
            "minecraft:rooted_dirt",
            "minecraft:grass_block",
            "minecraft:podzol",
            "minecraft:mycelium",
            "minecraft:mud",
            "minecraft:clay",
            "minecraft:sandstone",
            "minecraft:red_sandstone",
            "minecraft:snow",
            "minecraft:snow_block",
            "minecraft:netherrack",
            "minecraft:basalt",
            "minecraft:smooth_basalt",
            "minecraft:blackstone",
            "minecraft:end_stone",
            "minecraft:oak_leaves",
            "minecraft:spruce_leaves",
            "minecraft:birch_leaves",
            "minecraft:jungle_leaves",
            "minecraft:acacia_leaves",
            "minecraft:dark_oak_leaves",
            "minecraft:mangrove_leaves",
            "minecraft:cherry_leaves",
            "minecraft:azalea_leaves",
            "minecraft:flowering_azalea_leaves",
            "minecraft:pale_oak_leaves"
    );

    private static final Set<String> NEVER_BREAK_BLOCKS = Set.of(
            "minecraft:bedrock",
            "minecraft:barrier",
            "minecraft:end_portal",
            "minecraft:end_portal_frame",
            "minecraft:nether_portal",
            "minecraft:command_block",
            "minecraft:chain_command_block",
            "minecraft:repeating_command_block",
            "minecraft:structure_block",
            "minecraft:jigsaw",
            "minecraft:light",
            "minecraft:cactus",
            "minecraft:magma_block",
            "minecraft:campfire",
            "minecraft:soul_campfire",
            "minecraft:fire",
            "minecraft:soul_fire",
            "minecraft:powder_snow",
            "minecraft:sweet_berry_bush",
            "minecraft:wither_rose"
    );

    public static AutoNavigationConfig defaults() {
        return new AutoNavigationConfig(
                false,
                ListMode.WHITELIST,
                DEFAULT_NATURAL_BREAK_BLOCKS,
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
                16,
                WaterTransitPolicy.DEFAULT_MINIMUM_BOAT_DISTANCE_BLOCKS
        );
    }

    /**
     * Aggressive movement keeps bridging available and permits a conservative natural-terrain
     * break whitelist. The planner still exhausts non-modifying routes first and assigns breaking
     * a very high cost, so this is an escape hatch rather than the preferred route.
     */
    public static AutoNavigationConfig aggressiveDefaults() {
        AutoNavigationConfig safe = defaults();
        return new AutoNavigationConfig(
                true,
                safe.breakListMode,
                safe.breakBlocks,
                true,
                safe.placeListMode,
                safe.placeBlocks,
                safe.eatingEnabled,
                safe.foodListMode,
                safe.foods,
                safe.eatAtFoodLevel,
                safe.minimumBoatDistanceBlocks
        );
    }

    public boolean allowsBreak(String blockId) {
        return blockBreakingEnabled
                && !NEVER_BREAK_BLOCKS.contains(blockId)
                && breakListMode.allows(breakBlocks, blockId);
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
