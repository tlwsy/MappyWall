package dev.mappywall.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CrossProjectMapIdIndexTest {
    private final MapWallPlanner planner = new MapWallPlanner();

    @Test
    void sameMapIdForSameRegionAcrossJobsIsNotAConflict() {
        MapWallSave first = save("a", 0, 4);
        MapWallSave second = save("b", 0, 4);

        assertTrue(new CrossProjectMapIdIndex().findConflicts(List.of(first, second)).isEmpty());
    }

    @Test
    void reportsAllJobsWhenSameMapIdIsPersistedForDifferentRegions() {
        MapWallSave first = save("a", 0, 4);
        MapWallSave second = save("b", 1, 4);

        List<CrossProjectMapIdIndex.Conflict> conflicts =
                new CrossProjectMapIdIndex().findConflicts(List.of(first, second));

        assertEquals(1, conflicts.size());
        assertEquals(4, conflicts.getFirst().mapId());
        assertEquals(java.util.Set.of("a", "b"), conflicts.getFirst().involvedProjectIds());
        assertEquals(2, conflicts.getFirst().projectIdsByRegion().size());
    }

    @Test
    void higherScaleZoomAncestorDoesNotConflictWithItsActualScaleZeroJob() {
        MapWallSave highScale = planner.createSave(planner.createProject(
                "high", "local", "minecraft:overworld", 4, 1, 1, 0, 0, RunMode.MANUAL
        ));
        RouteStep highTarget = highScale.route().getFirst();
        highScale = highScale.withBindings(List.of(new MapBinding(
                highTarget.wallPos(),
                highTarget.region().signature(),
                5,
                Instant.EPOCH,
                BindingVerification.MAP_STATE
        )));
        MapWallSave scaleZero = save("zero", 0, 5);

        assertTrue(new CrossProjectMapIdIndex().findConflicts(List.of(highScale, scaleZero)).isEmpty());
    }

    private MapWallSave save(String id, int anchorX, int mapId) {
        MapWallProject project = planner.createProject(
                id,
                "local",
                "minecraft:overworld",
                0,
                1,
                1,
                anchorX * 128.0,
                0,
                RunMode.MANUAL
        );
        return planner.bindCurrentStep(
                planner.createSave(project),
                mapId,
                Instant.EPOCH,
                BindingVerification.TARGET_SCALE
        );
    }
}
