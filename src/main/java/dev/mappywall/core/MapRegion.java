package dev.mappywall.core;

import java.util.Objects;

public record MapRegion(
        String dimension,
        int scale,
        int gridX,
        int gridZ,
        int centerX,
        int centerZ,
        MapBounds bounds
) {
    public MapRegion {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(bounds, "bounds");
        MapRegionMath.validateScale(scale);
    }

    public String signature() {
        return dimension + "|" + scale + "|" + centerX + "|" + centerZ;
    }
}

