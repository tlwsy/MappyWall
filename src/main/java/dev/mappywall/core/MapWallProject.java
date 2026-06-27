package dev.mappywall.core;

import java.time.Instant;
import java.util.Objects;

public record MapWallProject(
        String id,
        String serverKey,
        String dimension,
        int scale,
        int width,
        int height,
        MapRegion anchorRegion,
        RunMode mode,
        ProjectStatus status,
        Instant createdAt
) {
    public MapWallProject {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(serverKey, "serverKey");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(anchorRegion, "anchorRegion");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        MapRegionMath.validateScale(scale);
        if (width <= 0) {
            throw new IllegalArgumentException("width must be positive");
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height must be positive");
        }
    }

    public MapWallProject withStatus(ProjectStatus newStatus) {
        return new MapWallProject(id, serverKey, dimension, scale, width, height, anchorRegion, mode, newStatus, createdAt);
    }
}

