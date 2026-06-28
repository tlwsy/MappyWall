package dev.mappywall.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void ignoresObservedMapThatDuplicatesBoundRegion() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject("p1", "local", "minecraft:overworld", 0, 1, 1, 0, 0, RunMode.MANUAL);
        MapWallSave save = planner.bindCurrentStep(planner.createSave(project), 3, Instant.EPOCH, BindingVerification.TARGET_CAPTURE);
        RouteStep first = save.route().getFirst();

        ObservedMap duplicate = new ObservedMap(9, "minecraft:overworld", 0, first.region().centerX(), first.region().centerZ());
        BindingRepairResult result = new InventoryMapIndex().repairManualOpenings(save, List.of(duplicate), Instant.EPOCH);

        assertEquals(1, result.bindings().size());
        assertFalse(result.hasWarnings());
    }

    @Test
    void warnsInsteadOfGuessingWhenMultipleMapsMatchUnboundRegion() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject("p1", "local", "minecraft:overworld", 0, 1, 1, 0, 0, RunMode.MANUAL);
        MapWallSave save = planner.createSave(project);
        RouteStep first = save.route().getFirst();

        ObservedMap firstCandidate = new ObservedMap(8, "minecraft:overworld", 0, first.region().centerX(), first.region().centerZ());
        ObservedMap secondCandidate = new ObservedMap(44, "minecraft:overworld", 0, first.region().centerX(), first.region().centerZ());

        BindingRepairResult result = new InventoryMapIndex().repairManualOpenings(
                save,
                List.of(secondCandidate, firstCandidate),
                Instant.EPOCH
        );

        assertEquals(0, result.bindings().size());
        assertTrue(result.hasWarnings());
        assertTrue(result.warnings().getFirst().contains("多个地图 [8, 44]"));
    }

    @Test
    void warnsWhenPreviouslyBoundMapIdReportsDifferentRegion() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject("p1", "local", "minecraft:overworld", 0, 2, 1, 0, 0, RunMode.MANUAL);
        MapWallSave save = planner.createSave(project);
        RouteStep first = save.route().getFirst();
        save = save.withBindings(List.of(new MapBinding(
                first.wallPos(),
                first.region().signature(),
                12,
                Instant.EPOCH,
                BindingVerification.MAP_STATE
        )));
        RouteStep second = save.route().get(1);

        ObservedMap conflicting = new ObservedMap(
                12,
                "minecraft:overworld",
                0,
                second.region().centerX(),
                second.region().centerZ()
        );

        BindingRepairResult result = new InventoryMapIndex().repairManualOpenings(save, List.of(conflicting), Instant.EPOCH);

        assertEquals(1, result.bindings().size());
        assertTrue(result.hasWarnings());
        assertTrue(result.warnings().getFirst().contains("地图 12 已绑定"));
    }

    @Test
    void ignoresStaleMapStateForFreshTargetCaptureBinding() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject("p1", "local", "minecraft:overworld", 0, 2, 1, 0, 0, RunMode.MANUAL);
        MapWallSave save = planner.createSave(project);
        RouteStep first = save.route().getFirst();
        RouteStep second = save.route().get(1);
        save = save.withBindings(List.of(new MapBinding(
                second.wallPos(),
                second.region().signature(),
                14,
                Instant.EPOCH,
                BindingVerification.TARGET_CAPTURE
        )));

        ObservedMap actual = new ObservedMap(
                14,
                "minecraft:overworld",
                0,
                first.region().centerX(),
                first.region().centerZ()
        );

        BindingRepairResult result = new InventoryMapIndex().repairManualOpenings(save, List.of(actual), Instant.EPOCH);

        assertFalse(result.hasWarnings());
        assertEquals(second.wallPos(), result.bindings().getFirst().wallPos());
        assertEquals(second.region().signature(), result.bindings().getFirst().regionSignature());
        assertEquals(BindingVerification.TARGET_CAPTURE, result.bindings().getFirst().verifiedBy());
    }

    @Test
    void upgradesTargetCaptureBindingWhenMapStateAgrees() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject("p1", "local", "minecraft:overworld", 0, 2, 1, 0, 0, RunMode.MANUAL);
        MapWallSave save = planner.createSave(project);
        RouteStep second = save.route().get(1);
        save = save.withBindings(List.of(new MapBinding(
                second.wallPos(),
                second.region().signature(),
                14,
                Instant.EPOCH,
                BindingVerification.TARGET_CAPTURE
        )));

        ObservedMap actual = new ObservedMap(
                14,
                "minecraft:overworld",
                0,
                second.region().centerX(),
                second.region().centerZ()
        );

        BindingRepairResult result = new InventoryMapIndex().repairManualOpenings(save, List.of(actual), Instant.EPOCH);

        assertFalse(result.hasWarnings());
        assertEquals(second.wallPos(), result.bindings().getFirst().wallPos());
        assertEquals(second.region().signature(), result.bindings().getFirst().regionSignature());
        assertEquals(BindingVerification.MAP_STATE, result.bindings().getFirst().verifiedBy());
    }

    @Test
    void repairsScaleZeroMapOpenedInsideHigherScaleRegion() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject("p1", "local", "minecraft:overworld", 2, 2, 1, 0, 0, RunMode.MANUAL);
        MapWallSave save = planner.createSave(project);
        RouteStep first = save.route().getFirst();

        ObservedMap openedScaleZeroMap = new ObservedMap(
                50,
                "minecraft:overworld",
                0,
                first.region().centerX(),
                first.region().centerZ()
        );

        BindingRepairResult result = new InventoryMapIndex().repairManualOpenings(
                save,
                List.of(openedScaleZeroMap),
                Instant.EPOCH
        );

        assertFalse(result.hasWarnings());
        assertEquals(1, result.bindings().size());
        assertEquals(first.wallPos(), result.bindings().getFirst().wallPos());
        assertEquals(first.region().signature(), result.bindings().getFirst().regionSignature());
        assertEquals(50, result.bindings().getFirst().mapId());
    }

    @Test
    void ignoresScaleZeroMapInsideAlreadyBoundHigherScaleRegion() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject("p1", "local", "minecraft:overworld", 2, 2, 1, 0, 0, RunMode.MANUAL);
        MapWallSave save = planner.createSave(project);
        RouteStep first = save.route().getFirst();
        RouteStep second = save.route().get(1);
        save = save.withBindings(List.of(new MapBinding(
                first.wallPos(),
                first.region().signature(),
                50,
                Instant.EPOCH,
                BindingVerification.TARGET_CAPTURE
        )));

        ObservedMap alreadyBoundScaleZeroMap = new ObservedMap(
                50,
                "minecraft:overworld",
                0,
                first.region().centerX(),
                first.region().centerZ()
        );
        ObservedMap nextScaleZeroMap = new ObservedMap(
                51,
                "minecraft:overworld",
                0,
                second.region().centerX(),
                second.region().centerZ()
        );

        BindingRepairResult result = new InventoryMapIndex().repairManualOpenings(
                save,
                List.of(alreadyBoundScaleZeroMap, nextScaleZeroMap),
                Instant.EPOCH
        );

        assertFalse(result.hasWarnings());
        assertEquals(2, result.bindings().size());
        assertEquals(second.region().signature(), result.bindings().get(1).regionSignature());
        assertEquals(51, result.bindings().get(1).mapId());
    }
}
