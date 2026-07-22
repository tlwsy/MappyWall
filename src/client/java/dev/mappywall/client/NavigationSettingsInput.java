package dev.mappywall.client;

import dev.mappywall.core.WaterTransitPolicy;
import java.util.OptionalInt;

final class NavigationSettingsInput {
    private NavigationSettingsInput() {}

    static OptionalInt parseMinimumBoatDistance(String value) {
        if (value == null) {
            return OptionalInt.empty();
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed >= WaterTransitPolicy.MINIMUM_BOAT_DISTANCE_BLOCKS
                            && parsed <= WaterTransitPolicy.MAXIMUM_BOAT_DISTANCE_BLOCKS
                    ? OptionalInt.of(parsed)
                    : OptionalInt.empty();
        } catch (NumberFormatException invalid) {
            return OptionalInt.empty();
        }
    }
}
