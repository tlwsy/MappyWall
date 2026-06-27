package dev.mappywall.core;

import java.time.Instant;
import java.util.Objects;

public record MapBinding(
        WallPos wallPos,
        String regionSignature,
        int mapId,
        Instant openedAt,
        BindingVerification verifiedBy
) {
    public MapBinding {
        Objects.requireNonNull(wallPos, "wallPos");
        Objects.requireNonNull(regionSignature, "regionSignature");
        Objects.requireNonNull(openedAt, "openedAt");
        Objects.requireNonNull(verifiedBy, "verifiedBy");
        if (mapId < 0) {
            throw new IllegalArgumentException("mapId must be non-negative");
        }
    }
}

