package dev.mappywall.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
    void saveStartsUnpausedAtFirstStep() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject("p1", "local", "minecraft:overworld", 2, 2, 2, 10, 20, RunMode.MANUAL);
        MapWallSave save = planner.createSave(project);

        assertEquals(0, save.session().currentStep());
        assertFalse(save.session().paused());
        assertEquals(4, save.route().size());
    }
}

