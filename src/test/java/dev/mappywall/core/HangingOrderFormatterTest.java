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
                new MapBinding(topRight.wallPos(), topRight.region().signature(), 8, Instant.EPOCH, BindingVerification.MAP_STATE)
        ));

        List<String> lines = new HangingOrderFormatter().format(save);

        assertTrue(lines.get(0).contains("row 0, column 1"));
        assertTrue(lines.get(1).contains("row 1, column 0"));
    }
}
