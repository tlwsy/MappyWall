package dev.mappywall.core;

import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/** Selects the best owned map for the next cartography/fill action. */
public final class MapCandidateSelector {
    public Optional<ObservedMap> selectHighestScale(
            Collection<ObservedMap> observations,
            Integer cartographyInputMapId,
            Integer offhandMapId
    ) {
        Objects.requireNonNull(observations, "observations");
        return observations.stream()
                .map(observed -> Objects.requireNonNull(observed, "observed map"))
                .sorted(Comparator
                        .comparingInt(ObservedMap::scale).reversed()
                        .thenComparing(observed -> !Objects.equals(observed.mapId(), cartographyInputMapId))
                        .thenComparing(observed -> !Objects.equals(observed.mapId(), offhandMapId))
                        .thenComparingInt(ObservedMap::mapId))
                .findFirst();
    }
}
