package dev.mappywall.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Persisted identity for one empty-map use before its filled-map id arrives. */
public record PendingMapOpening(
        String regionSignature,
        Set<Integer> knownMapIds,
        int expectedInventorySlot,
        Instant startedAt
) {
    public static final int OFFHAND_SLOT = -1;

    public PendingMapOpening {
        Objects.requireNonNull(regionSignature, "regionSignature");
        Objects.requireNonNull(knownMapIds, "knownMapIds");
        Objects.requireNonNull(startedAt, "startedAt");
        if (expectedInventorySlot < OFFHAND_SLOT) {
            throw new IllegalArgumentException("expectedInventorySlot is invalid");
        }
        knownMapIds = Set.copyOf(knownMapIds);
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
