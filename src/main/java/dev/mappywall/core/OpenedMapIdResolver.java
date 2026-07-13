package dev.mappywall.core;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Resolves the filled-map id produced by one pending empty-map use. */
public final class OpenedMapIdResolver {
    public Optional<Integer> resolve(Set<Integer> knownMapIds, Set<Integer> currentMapIds, Integer pendingSlotMapId) {
        Objects.requireNonNull(knownMapIds, "knownMapIds");
        Objects.requireNonNull(currentMapIds, "currentMapIds");

        Set<Integer> newMapIds = new HashSet<>(currentMapIds);
        newMapIds.removeAll(knownMapIds);
        return pendingSlotMapId != null && newMapIds.contains(pendingSlotMapId)
                ? Optional.of(pendingSlotMapId)
                : Optional.empty();
    }
}
