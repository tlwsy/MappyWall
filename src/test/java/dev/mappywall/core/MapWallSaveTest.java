package dev.mappywall.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MapWallSaveTest {
    @Test
    void rejectsRouteWhoseSizeDoesNotMatchProjectDimensions() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "p1", "local", "minecraft:overworld", 0, 2, 1, 0, 0, RunMode.MANUAL
        );
        MapWallSave save = planner.createSave(project);

        assertThrows(IllegalArgumentException.class, () -> new MapWallSave(
                save.schemaVersion(), project, List.of(save.route().getFirst()), List.of(), save.session()
        ));
    }

    @Test
    void rejectsBindingWhoseWallPositionDoesNotMatchRouteRegion() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "p1", "local", "minecraft:overworld", 0, 2, 1, 0, 0, RunMode.MANUAL
        );
        MapWallSave save = planner.createSave(project);
        RouteStep first = save.route().getFirst();

        assertThrows(IllegalArgumentException.class, () -> save.withBindings(List.of(new MapBinding(
                new WallPos(1, 0),
                first.region().signature(),
                4,
                Instant.EPOCH,
                BindingVerification.MAP_STATE
        ))));
    }

    @Test
    void safelyMergesDuplicateCopiesOfSameBinding() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallSave save = planner.createSave(planner.createProject(
                "p1", "local", "minecraft:overworld", 0, 1, 1, 0, 0, RunMode.MANUAL
        ));
        RouteStep step = save.route().getFirst();
        MapBinding targetCapture = new MapBinding(
                step.wallPos(), step.region().signature(), 8, Instant.EPOCH.plusSeconds(1), BindingVerification.TARGET_CAPTURE
        );
        MapBinding mapState = new MapBinding(
                step.wallPos(), step.region().signature(), 8, Instant.EPOCH, BindingVerification.MAP_STATE
        );

        MapWallSave normalized = save.withBindings(List.of(targetCapture, mapState));

        assertEquals(1, normalized.bindings().size());
        assertEquals(Instant.EPOCH, normalized.bindings().getFirst().openedAt());
        assertEquals(BindingVerification.MAP_STATE, normalized.bindings().getFirst().verifiedBy());
    }

    @Test
    void allowsDifferentMapIdsForSameRegion() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallSave save = planner.createSave(planner.createProject(
                "p1", "local", "minecraft:overworld", 0, 1, 1, 0, 0, RunMode.MANUAL
        ));
        RouteStep step = save.route().getFirst();

        MapWallSave normalized = save.withBindings(List.of(
                new MapBinding(
                        step.wallPos(), step.region().signature(), 4, Instant.EPOCH, BindingVerification.MAP_STATE
                ),
                new MapBinding(
                        step.wallPos(), step.region().signature(), 5, Instant.EPOCH, BindingVerification.MAP_STATE
                )
        ));

        assertEquals(List.of(4, 5), normalized.bindingsForRegion(step.region().signature()).stream()
                .map(MapBinding::mapId)
                .toList());
        assertEquals(4, normalized.preferredBindingForRegion(step.region().signature()).orElseThrow().mapId());
    }

    @Test
    void rejectsSameMapIdBoundToDifferentRegions() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallSave save = planner.createSave(planner.createProject(
                "p1", "local", "minecraft:overworld", 0, 2, 1, 0, 0, RunMode.MANUAL
        ));
        RouteStep first = save.route().getFirst();
        RouteStep second = save.route().get(1);

        assertThrows(IllegalArgumentException.class, () -> save.withBindings(List.of(
                new MapBinding(
                        first.wallPos(), first.region().signature(), 8, Instant.EPOCH, BindingVerification.MAP_STATE
                ),
                new MapBinding(
                        second.wallPos(), second.region().signature(), 8, Instant.EPOCH, BindingVerification.MAP_STATE
                )
        )));
    }
}
