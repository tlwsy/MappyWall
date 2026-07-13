package dev.mappywall.core;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Resolves the new map id produced by a known cartography-table scale operation. */
public final class ZoomedMapIdResolver {
    public Optional<Integer> resolve(
            PendingMapZoom pending,
            List<ObservedMap> observations,
            int currentSourceMapCount
    ) {
        Objects.requireNonNull(pending, "pending");
        if (currentSourceMapCount >= pending.sourceMapCount()) {
            return Optional.empty();
        }
        return resolve(pending.knownMapIds(), observations, pending.expectedScale());
    }

    /** Resolves lineage from owned item ids even when the server MapState packet has not arrived yet. */
    public Optional<Integer> resolve(
            PendingMapZoom pending,
            Set<Integer> currentMapIds,
            int currentSourceMapCount
    ) {
        Objects.requireNonNull(pending, "pending");
        Objects.requireNonNull(currentMapIds, "currentMapIds");
        if (currentSourceMapCount >= pending.sourceMapCount()) {
            return Optional.empty();
        }
        Set<Integer> candidates = new LinkedHashSet<>(currentMapIds);
        candidates.removeAll(pending.knownMapIds());
        return candidates.size() == 1
                ? Optional.of(candidates.iterator().next())
                : Optional.empty();
    }

    public Optional<Integer> resolve(Set<Integer> knownMapIds, List<ObservedMap> observations, int expectedScale) {
        Objects.requireNonNull(knownMapIds, "knownMapIds");
        Objects.requireNonNull(observations, "observations");
        MapRegionMath.validateScale(expectedScale);

        Set<Integer> candidates = new LinkedHashSet<>();
        for (ObservedMap observed : observations) {
            Objects.requireNonNull(observed, "observed map");
            if (!knownMapIds.contains(observed.mapId()) && observed.scale() == expectedScale) {
                candidates.add(observed.mapId());
            }
        }
        return candidates.size() == 1
                ? Optional.of(candidates.iterator().next())
                : Optional.empty();
    }
}
