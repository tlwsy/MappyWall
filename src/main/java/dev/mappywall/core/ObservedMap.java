package dev.mappywall.core;

import java.util.Objects;

public record ObservedMap(
        int mapId,
        String dimension,
        int scale,
        int centerX,
        int centerZ,
        double exploredFraction,
        boolean regionReliable
) {
    public ObservedMap(int mapId, String dimension, int scale, int centerX, int centerZ) {
        this(mapId, dimension, scale, centerX, centerZ, -1.0, true);
    }

    public ObservedMap(
            int mapId,
            String dimension,
            int scale,
            int centerX,
            int centerZ,
            double exploredFraction
    ) {
        this(mapId, dimension, scale, centerX, centerZ, exploredFraction, true);
    }

    public ObservedMap {
        Objects.requireNonNull(dimension, "dimension");
        if (mapId < 0) {
            throw new IllegalArgumentException("mapId must be non-negative");
        }
        MapRegionMath.validateScale(scale);
        if (Double.isNaN(exploredFraction)) {
            exploredFraction = -1.0;
        } else {
            exploredFraction = Math.max(-1.0, Math.min(1.0, exploredFraction));
        }
    }

    public String regionSignature() {
        return dimension + "|" + scale + "|" + centerX + "|" + centerZ;
    }
}
