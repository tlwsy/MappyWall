package dev.mappywall.core;

import dev.mappywall.core.PathSegmentCoordinator.Anchor;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Builds a small immutable set of columns pointing from a future seam toward its target. */
public final class NavigationTerrainProbe {
    private static final int MAX_FORWARD_PROBES = 4;

    private NavigationTerrainProbe() {
    }

    public static List<Anchor> targetDirectedColumns(
            Anchor seam,
            Anchor target,
            int maxDistance
    ) {
        Objects.requireNonNull(seam, "seam");
        Objects.requireNonNull(target, "target");
        if (maxDistance <= 0) {
            throw new IllegalArgumentException("maxDistance must be positive");
        }

        long deltaX = (long) target.x() - seam.x();
        long deltaZ = (long) target.z() - seam.z();
        double horizontalDistance = Math.hypot(deltaX, deltaZ);
        if (horizontalDistance == 0.0) {
            return List.of(seam);
        }

        int probeCount = Math.min(
                MAX_FORWARD_PROBES,
                (int) Math.ceil(Math.min(horizontalDistance, maxDistance))
        );
        Set<Anchor> columns = new LinkedHashSet<>();
        columns.add(seam);
        for (int index = 1; index <= probeCount; index++) {
            double probeDistance = Math.min(index, horizontalDistance);
            int x = seam.x() + (int) Math.round(deltaX / horizontalDistance * probeDistance);
            int z = seam.z() + (int) Math.round(deltaZ / horizontalDistance * probeDistance);
            columns.add(new Anchor(x, seam.y(), z));
        }
        return List.copyOf(columns);
    }
}
