package dev.mappywall.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MapWallPlannerTest {
    @Test
    void plansSnakeRouteForThreeByTwoWall() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject("p1", "local", "minecraft:overworld", 0, 3, 2, 0, 0, RunMode.MANUAL);

        List<RouteStep> route = planner.planRoute(project);

        assertEquals(new WallPos(0, 0), route.get(0).wallPos());
        assertEquals(new WallPos(1, 0), route.get(1).wallPos());
        assertEquals(new WallPos(2, 0), route.get(2).wallPos());
        assertEquals(new WallPos(2, 1), route.get(3).wallPos());
        assertEquals(new WallPos(1, 1), route.get(4).wallPos());
        assertEquals(new WallPos(0, 1), route.get(5).wallPos());
    }

    @Test
    void centersEvenSizedWallAroundPlayerRegion() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "p1",
                "local",
                "minecraft:overworld",
                0,
                2,
                2,
                0,
                0,
                RunMode.MANUAL,
                WallAnchorMode.CENTER,
                1,
                1
        );

        List<RouteStep> route = planner.planRoute(project, 1, 1);

        assertEquals(-128, route.get(0).region().centerX());
        assertEquals(-128, route.get(0).region().centerZ());
        assertEquals(0, route.get(1).region().centerX());
        assertEquals(-128, route.get(1).region().centerZ());
        assertEquals(0, route.get(2).region().centerX());
        assertEquals(0, route.get(2).region().centerZ());
        assertEquals(-128, route.get(3).region().centerX());
        assertEquals(0, route.get(3).region().centerZ());
    }

    @Test
    void plansRouteTowardWestAndNorthWhenSelected() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "p1",
                "local",
                "minecraft:overworld",
                0,
                2,
                2,
                0,
                0,
                RunMode.MANUAL,
                WallAnchorMode.FIRST_REGION,
                -1,
                -1
        );

        List<RouteStep> route = planner.planRoute(project, -1, -1);

        assertEquals(0, route.get(0).region().centerX());
        assertEquals(0, route.get(0).region().centerZ());
        assertEquals(-128, route.get(1).region().centerX());
        assertEquals(0, route.get(1).region().centerZ());
        assertEquals(-128, route.get(2).region().centerX());
        assertEquals(-128, route.get(2).region().centerZ());
        assertEquals(0, route.get(3).region().centerX());
        assertEquals(-128, route.get(3).region().centerZ());
    }

    @Test
    void bindingAdvancesCurrentStepWithoutAssumingContiguousMapIds() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject("p1", "local", "minecraft:overworld", 0, 2, 1, 0, 0, RunMode.MANUAL);
        MapWallSave save = planner.createSave(project);

        MapWallSave afterFirst = planner.bindCurrentStep(save, 8, Instant.EPOCH, BindingVerification.TARGET_CAPTURE);
        MapWallSave afterSecond = planner.bindCurrentStep(afterFirst, 44, Instant.EPOCH, BindingVerification.TARGET_CAPTURE);

        assertEquals(List.of(8, 44), afterSecond.bindings().stream().map(MapBinding::mapId).toList());
        assertEquals(ProjectStatus.COMPLETE, afterSecond.project().status());
    }

    @Test
    void fillAfterOpenBindsMapBeforeRunningFillWaypoints() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "p1",
                "local",
                "minecraft:overworld",
                0,
                1,
                1,
                0,
                0,
                RunMode.AUTO_WALK,
                WallAnchorMode.FIRST_REGION,
                1,
                1,
                PostOpenMode.FILL_AFTER_OPEN
        );
        MapWallSave save = planner.createSave(project);

        MapWallSave afterOpen = planner.bindCurrentStep(save, 5, Instant.EPOCH, BindingVerification.TARGET_CAPTURE);

        assertEquals(List.of(5), afterOpen.bindings().stream().map(MapBinding::mapId).toList());
        assertEquals(ProjectStatus.RUNNING, afterOpen.project().status());
        assertEquals(RouteStepState.OPENED, afterOpen.route().getFirst().state());
        assertNotNull(planner.nextFillStep(afterOpen));

        MapWallSave cursor = afterOpen;
        int fillWaypointCount = planner.fillWaypointCount(afterOpen.route().getFirst().region());
        for (int i = 0; i < fillWaypointCount; i++) {
            cursor = planner.advanceFillWaypoint(cursor);
        }

        assertEquals(RouteStepState.BOUND, cursor.route().getFirst().state());
        assertNull(planner.nextFillStep(cursor));
        assertEquals(ProjectStatus.COMPLETE, cursor.project().status());
    }

    @Test
    void saveStartsUnpausedAtFirstStep() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject("p1", "local", "minecraft:overworld", 2, 2, 2, 10, 20, RunMode.MANUAL);
        MapWallSave save = planner.createSave(project);

        assertEquals(0, save.session().currentStep());
        assertFalse(save.session().paused());
        assertEquals(4, save.route().size());
    }
}
