package dev.mappywall.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class HangingOrderFormatterTest {
    @Test
    void formatsBindingsInRowMajorWallOrder() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject("p1", "local", "minecraft:overworld", 0, 2, 2, 0, 0, RunMode.MANUAL);
        MapWallSave save = planner.createSave(project);
        RouteStep bottomLeft = save.route().stream().filter(step -> step.wallPos().equals(new WallPos(0, 1))).findFirst().orElseThrow();
        RouteStep topRight = save.route().stream().filter(step -> step.wallPos().equals(new WallPos(1, 0))).findFirst().orElseThrow();
        save = save.withBindings(List.of(
                new MapBinding(bottomLeft.wallPos(), bottomLeft.region().signature(), 44, Instant.EPOCH, BindingVerification.MAP_STATE),
                new MapBinding(topRight.wallPos(), topRight.region().signature(), 8, Instant.EPOCH, BindingVerification.MAP_STATE),
                new MapBinding(topRight.wallPos(), topRight.region().signature(), 5, Instant.EPOCH, BindingVerification.MAP_STATE)
        ));

        List<String> lines = new HangingOrderFormatter().format(save);

        assertTrue(lines.get(0).contains("1. row 1, column 2"));
        assertTrue(lines.get(0).contains("map #5/8"));
        assertTrue(lines.get(1).contains("2. row 2, column 1"));
    }

    @Test
    void excludesLowerScaleZoomAncestorsFromHangingChoices() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallSave save = planner.createSave(planner.createProject(
                "scaled", "local", "minecraft:overworld", 3, 1, 1, 0, 0, RunMode.MANUAL
        ));
        RouteStep step = save.route().getFirst();
        save = save.withBindings(List.of(
                new MapBinding(step.wallPos(), step.region().signature(), 4, Instant.EPOCH, BindingVerification.MAP_STATE),
                new MapBinding(step.wallPos(), step.region().signature(), 9, Instant.EPOCH, BindingVerification.TARGET_SCALE)
        ));

        List<String> lines = new HangingOrderFormatter().format(save);

        assertTrue(lines.getFirst().contains("map #9"));
        assertTrue(!lines.getFirst().contains("4/9"));
    }
}
