package dev.mappywall.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class InventoryMapIndexTest {
    @Test
    void repairsManualMapOpenWithNonContiguousMapIds() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject("p1", "local", "minecraft:overworld", 1, 2, 1, 0, 0, RunMode.MANUAL);
        MapWallSave save = planner.createSave(project);
        RouteStep second = save.route().get(1);

        ObservedMap observed = new ObservedMap(
                42,
                second.region().dimension(),
                second.region().scale(),
                second.region().centerX(),
                second.region().centerZ()
        );

        BindingRepairResult result = new InventoryMapIndex().repairManualOpenings(save, List.of(observed), Instant.EPOCH);

        assertEquals(1, result.bindings().size());
        assertEquals(42, result.bindings().getFirst().mapId());
        assertEquals(second.wallPos(), result.bindings().getFirst().wallPos());
        assertEquals(BindingVerification.MANUAL_REPAIR, result.bindings().getFirst().verifiedBy());
    }

    @Test
    void warnsWhenObservedMapDuplicatesBoundRegion() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject("p1", "local", "minecraft:overworld", 0, 1, 1, 0, 0, RunMode.MANUAL);
        MapWallSave save = planner.bindCurrentStep(planner.createSave(project), 3, Instant.EPOCH, BindingVerification.TARGET_CAPTURE);
        RouteStep first = save.route().getFirst();

        ObservedMap duplicate = new ObservedMap(9, "minecraft:overworld", 0, first.region().centerX(), first.region().centerZ());
        BindingRepairResult result = new InventoryMapIndex().repairManualOpenings(save, List.of(duplicate), Instant.EPOCH);

        assertEquals(1, result.bindings().size());
        assertTrue(result.hasWarnings());
    }
}

