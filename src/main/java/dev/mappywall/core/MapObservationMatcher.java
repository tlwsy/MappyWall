package dev.mappywall.core;

import java.util.Objects;
import java.util.Optional;

/** Matches client-visible map data to a persisted route binding without trusting missing packet fields. */
public final class MapObservationMatcher {
    public boolean matchesBoundRegion(MapWallSave save, RouteStep target, ObservedMap observed) {
        Objects.requireNonNull(save, "save");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(observed, "observed");

        Optional<MapBinding> binding = save.bindingForMapId(observed.mapId());
        if (binding.isEmpty()
                || !binding.get().regionSignature().equals(target.region().signature())
                || observed.scale() > target.region().scale()) {
            return false;
        }
        if (!observed.regionReliable()) {
            return true;
        }
        if (!observed.dimension().equals(target.region().dimension())) {
            return false;
        }
        MapRegion projected = MapRegionMath.regionForBlock(
                observed.dimension(),
                target.region().scale(),
                observed.centerX(),
                observed.centerZ()
        );
        return projected.signature().equals(target.region().signature());
    }

    public boolean isExactTargetScale(MapWallSave save, RouteStep target, ObservedMap observed) {
        return matchesBoundRegion(save, target, observed)
                && observed.scale() == target.region().scale();
    }
}
