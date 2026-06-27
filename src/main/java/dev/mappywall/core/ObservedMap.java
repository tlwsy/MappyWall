package dev.mappywall.core;

import java.util.Objects;

public record ObservedMap(int mapId, String dimension, int scale, int centerX, int centerZ) {
    public ObservedMap {
        Objects.requireNonNull(dimension, "dimension");
        if (mapId < 0) {
            throw new IllegalArgumentException("mapId must be non-negative");
        }
        MapRegionMath.validateScale(scale);
    }

    public String regionSignature() {
        return dimension + "|" + scale + "|" + centerX + "|" + centerZ;
    }
}

