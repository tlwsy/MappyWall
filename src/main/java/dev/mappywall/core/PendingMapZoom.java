package dev.mappywall.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Persisted lineage for one committed cartography-table scale transaction. */
public record PendingMapZoom(
        String regionSignature,
        int sourceMapId,
        int expectedScale,
        Set<Integer> knownMapIds,
        int sourceMapCount,
        Instant startedAt
) {
    public PendingMapZoom {
        Objects.requireNonNull(regionSignature, "regionSignature");
        Objects.requireNonNull(knownMapIds, "knownMapIds");
        // Old JSON written before transaction timestamps existed deserializes this as null.
        // Treat it as stale so the normal pause/resume recovery path can discard it safely.
        if (startedAt == null) {
            startedAt = Instant.EPOCH;
        }
        if (sourceMapId < 0) {
            throw new IllegalArgumentException("sourceMapId must be non-negative");
        }
        MapRegionMath.validateScale(expectedScale);
        knownMapIds = Set.copyOf(knownMapIds);
        if (!knownMapIds.contains(sourceMapId)) {
            throw new IllegalArgumentException("knownMapIds must contain sourceMapId");
        }
        if (sourceMapCount < 0) {
            throw new IllegalArgumentException("sourceMapCount must be non-negative");
        }
        if (sourceMapCount == 0) {
            sourceMapCount = 1;
        }
    }

    public PendingMapZoom(
            String regionSignature,
            int sourceMapId,
            int expectedScale,
            Set<Integer> knownMapIds,
            int sourceMapCount
    ) {
        this(regionSignature, sourceMapId, expectedScale, knownMapIds, sourceMapCount, Instant.now());
    }

    public PendingMapZoom(String regionSignature, int sourceMapId, int expectedScale, Set<Integer> knownMapIds) {
        this(regionSignature, sourceMapId, expectedScale, knownMapIds, 1, Instant.now());
    }

    public boolean timedOutAt(Instant now, Duration timeout) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        return startedAt.isAfter(now) || Duration.between(startedAt, now).compareTo(timeout) >= 0;
    }
}
