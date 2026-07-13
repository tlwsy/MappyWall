package dev.mappywall.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

        MapWallSave afterFirst = planner.bindCurrentStep(save, 8, Instant.EPOCH, BindingVerification.MAP_STATE);
        MapWallSave afterSecond = planner.bindCurrentStep(afterFirst, 44, Instant.EPOCH, BindingVerification.MAP_STATE);

        assertEquals(List.of(8, 44), afterSecond.bindings().stream().map(MapBinding::mapId).toList());
        assertEquals(ProjectStatus.COMPLETE, afterSecond.project().status());
    }

    @Test
    void reconciliationDoesNotHideConflictOnOtherwiseCompleteRoute() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "conflict-complete", "local", "minecraft:overworld", 0, 1, 1, 0, 0, RunMode.MANUAL
        );
        MapWallSave complete = planner.bindCurrentStep(
                planner.createSave(project), 8, Instant.EPOCH, BindingVerification.MAP_STATE
        );
        MapWallSave conflict = complete
                .withProject(complete.project().withStatus(ProjectStatus.CONFLICT))
                .withSession(complete.session().withPaused(true).withWarnings(List.of("conflict")));

        MapWallSave reconciled = planner.reconcileBindings(conflict, conflict.bindings());

        assertEquals(ProjectStatus.CONFLICT, reconciled.project().status());
    }

    @Test
    void fillAfterOpenDoesNotSkipEarlierUnboundCellForFutureAlias() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "future-alias",
                "local",
                "minecraft:overworld",
                0,
                2,
                1,
                0,
                0,
                RunMode.AUTO_WALK,
                WallAnchorMode.FIRST_REGION,
                1,
                1,
                PostOpenMode.FILL_AFTER_OPEN,
                AutomationStyle.AGGRESSIVE
        );
        MapWallSave save = planner.createSave(project);
        RouteStep future = save.route().get(1);
        save = planner.reconcileBindings(save, List.of(new MapBinding(
                future.wallPos(),
                future.region().signature(),
                9,
                Instant.EPOCH,
                BindingVerification.MAP_STATE
        )));

        assertNull(planner.nextFillStep(save));
        assertEquals(save.route().getFirst(), planner.nextOpenStep(save));
    }

    @Test
    void openFirstAtLargerScaleWaitsForTargetScaleVerification() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "p1",
                "local",
                "minecraft:overworld",
                4,
                1,
                1,
                0,
                0,
                RunMode.MANUAL
        );
        MapWallSave opened = planner.bindCurrentStep(
                planner.createSave(project), 8, Instant.EPOCH, BindingVerification.POSITION_CAPTURE
        );

        assertEquals(RouteStepState.OPENED, opened.route().getFirst().state());
        assertEquals(ProjectStatus.RUNNING, opened.project().status());
        assertNull(planner.nextOpenStep(opened));

        MapBinding old = opened.bindings().getFirst();
        MapWallSave zoomed = planner.reconcileBindings(opened, List.of(new MapBinding(
                old.wallPos(),
                old.regionSignature(),
                44,
                old.openedAt(),
                BindingVerification.TARGET_SCALE
        )));

        assertEquals(RouteStepState.BOUND, zoomed.route().getFirst().state());
        assertEquals(ProjectStatus.COMPLETE, zoomed.project().status());
    }

    @Test
    void positionValidatedTargetCaptureCompletesScaleZeroCell() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "p1", "local", "minecraft:overworld", 0, 1, 1, 0, 0, RunMode.MANUAL
        );
        MapWallSave captured = planner.bindCurrentStep(
                planner.createSave(project), 8, Instant.EPOCH, BindingVerification.POSITION_CAPTURE
        );

        assertEquals(RouteStepState.BOUND, captured.route().getFirst().state());
        assertEquals(ProjectStatus.COMPLETE, captured.project().status());
    }

    @Test
    void legacyTargetCaptureAtScaleZeroRemainsProvisional() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "legacy-capture", "local", "minecraft:overworld", 0, 1, 1, 0, 0, RunMode.MANUAL
        );

        MapWallSave captured = planner.bindCurrentStep(
                planner.createSave(project), 8, Instant.EPOCH, BindingVerification.TARGET_CAPTURE
        );

        assertEquals(RouteStepState.OPENED, captured.route().getFirst().state());
        assertEquals(ProjectStatus.RUNNING, captured.project().status());
        assertEquals(captured.route().getFirst(), planner.nextOpenStep(captured));
    }

    @Test
    void fillAfterOpenReopensLegacyScaleZeroCaptureBeforeFilling() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "legacy-fill-scale-zero",
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
        MapWallSave captured = planner.bindCurrentStep(
                planner.createSave(project), 8, Instant.EPOCH, BindingVerification.TARGET_CAPTURE
        );

        assertNull(planner.nextFillStep(captured));
        assertEquals(captured.route().getFirst(), planner.nextOpenStep(captured));
    }

    @Test
    void migratesLegacyPlaceholderBasedBindingsBackToReopenableEvidence() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "legacy-placeholder", "local", "minecraft:overworld", 0, 2, 1, 0, 0, RunMode.AUTO_WALK
        );
        MapWallSave initial = planner.createSave(project);
        MapWallSave polluted = planner.reconcileBindings(initial, List.of(
                new MapBinding(
                        initial.route().get(0).wallPos(),
                        initial.route().get(0).region().signature(),
                        4,
                        Instant.EPOCH,
                        BindingVerification.MAP_STATE
                ),
                new MapBinding(
                        initial.route().get(1).wallPos(),
                        initial.route().get(1).region().signature(),
                        9,
                        Instant.EPOCH,
                        BindingVerification.TARGET_SCALE
                )
        )).withSession(initial.session().withBindingDataVersion(0));

        MapWallSave migrated = planner.migrateLegacyBindingData(polluted);

        assertEquals(RunSessionState.CURRENT_BINDING_DATA_VERSION, migrated.session().bindingDataVersion());
        assertTrue(migrated.bindings().stream()
                .allMatch(binding -> binding.verifiedBy() == BindingVerification.TARGET_CAPTURE));
        assertEquals(migrated.route().getFirst(), planner.nextOpenStep(migrated));
        assertEquals(ProjectStatus.RUNNING, migrated.project().status());
    }

    @Test
    void fillAfterOpenDemotesLegacyHighScaleCompletionWithoutTargetScaleMap() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "legacy-fill",
                "local",
                "minecraft:overworld",
                2,
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
        MapWallSave captured = planner.bindCurrentStep(
                planner.createSave(project), 8, Instant.EPOCH, BindingVerification.TARGET_CAPTURE
        );
        RouteStep step = captured.route().getFirst();
        MapWallSave legacyComplete = new MapWallSave(
                captured.schemaVersion(),
                captured.project().withStatus(ProjectStatus.COMPLETE),
                List.of(step.withState(RouteStepState.BOUND)),
                captured.bindings(),
                captured.session()
        );

        MapWallSave normalized = planner.reconcileBindings(legacyComplete, legacyComplete.bindings());

        assertEquals(RouteStepState.OPENED, normalized.route().getFirst().state());
        assertEquals(ProjectStatus.RUNNING, normalized.project().status());
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

        MapWallSave afterOpen = planner.bindCurrentStep(
                save, 5, Instant.EPOCH, BindingVerification.POSITION_CAPTURE
        );

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

    @Test
    void manualRepairReconcilesBindingRouteAndFillSessionTogether() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "p1",
                "local",
                "minecraft:overworld",
                1,
                2,
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
        RouteStep first = save.route().getFirst();
        BindingRepairResult repair = new InventoryMapIndex().repairManualOpenings(
                save,
                List.of(new ObservedMap(
                        77,
                        first.region().dimension(),
                        first.region().scale(),
                        first.region().centerX(),
                        first.region().centerZ()
                )),
                Instant.EPOCH
        );

        MapWallSave reconciled = planner.reconcileBindings(save, repair.bindings());

        assertEquals(RouteStepState.OPENED, reconciled.route().getFirst().state());
        assertEquals(RouteStepState.PENDING, reconciled.route().get(1).state());
        assertEquals(1, reconciled.session().currentStep());
        assertEquals(0, reconciled.session().fillWaypointIndex());
        assertEquals(first.region().signature(), planner.nextFillStep(reconciled).region().signature());
    }

    @Test
    void refusesToBindOneMapIdToTwoRouteRegions() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject("p1", "local", "minecraft:overworld", 0, 2, 1, 0, 0, RunMode.MANUAL);
        MapWallSave first = planner.bindCurrentStep(
                planner.createSave(project), 5, Instant.EPOCH, BindingVerification.MAP_STATE
        );

        MapWallSave unchanged = planner.bindCurrentStep(
                first, 5, Instant.EPOCH.plusSeconds(1), BindingVerification.MAP_STATE
        );

        assertEquals(first, unchanged);
        assertEquals(1, unchanged.bindings().size());
        assertEquals(1, unchanged.session().currentStep());
    }

    @Test
    void weakerAliasDoesNotDowngradeVerifiedRegionCompletion() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "aliases", "local", "minecraft:overworld", 1, 1, 1, 0, 0, RunMode.MANUAL
        );
        MapWallSave save = planner.createSave(project);
        RouteStep step = save.route().getFirst();

        MapWallSave reconciled = planner.reconcileBindings(save, List.of(
                new MapBinding(
                        step.wallPos(),
                        step.region().signature(),
                        4,
                        Instant.EPOCH,
                        BindingVerification.TARGET_SCALE
                ),
                new MapBinding(
                        step.wallPos(),
                        step.region().signature(),
                        5,
                        Instant.EPOCH,
                        BindingVerification.TARGET_CAPTURE
                )
        ));

        assertEquals(RouteStepState.BOUND, reconciled.route().getFirst().state());
        assertEquals(ProjectStatus.COMPLETE, reconciled.project().status());
        assertEquals(4, reconciled.preferredBindingForRegion(step.region().signature()).orElseThrow().mapId());
    }

    @Test
    void positionCapturedMapCanBeAddedAsAliasToAnAlreadyCompletedRegion() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "manual-alias", "local", "minecraft:overworld", 0, 2, 1, 0, 0, RunMode.MANUAL
        );
        MapWallSave first = planner.bindCurrentStep(
                planner.createSave(project), 4, Instant.EPOCH, BindingVerification.POSITION_CAPTURE
        );
        RouteStep completedRegion = first.route().getFirst();

        MapWallSave aliased = planner.bindStep(
                first, completedRegion, 5, Instant.EPOCH.plusSeconds(1), BindingVerification.POSITION_CAPTURE
        );

        assertEquals(List.of(4, 5), aliased.bindingsForRegion(completedRegion.region().signature()).stream()
                .map(MapBinding::mapId)
                .toList());
        assertEquals(first.route().get(1), planner.nextOpenStep(aliased));
    }

    @Test
    void fillTargetsAreGloballyUniqueEvenWhenCenterIsInSampleGrid() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "p1",
                "local",
                "minecraft:overworld",
                1,
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
        MapWallSave cursor = planner.bindCurrentStep(
                planner.createSave(project), 9, Instant.EPOCH, BindingVerification.MAP_STATE
        );
        Set<BlockTarget> targets = new HashSet<>();
        int count = planner.fillWaypointCount(cursor.route().getFirst().region());
        for (int index = 0; index < count; index++) {
            RouteStep fillStep = planner.nextFillStep(cursor);
            assertNotNull(fillStep);
            assertTrue(targets.add(planner.fillNavigationStep(cursor, fillStep).targetBlock()));
            cursor = planner.advanceFillWaypoint(cursor);
        }
        assertEquals(count, targets.size());
    }

    @Test
    void persistsSelectedDirectionsOnProjectAndUsesThemByDefault() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "p1", "local", "minecraft:overworld", 0, 2, 2, 0, 0, RunMode.MANUAL,
                WallAnchorMode.FIRST_REGION, -1, -1
        );

        MapWallSave save = planner.createSave(project);

        assertEquals(-1, save.project().columnStepX());
        assertEquals(-1, save.project().rowStepZ());
        assertEquals(-128, save.route().get(1).region().centerX());
        assertEquals(-128, save.route().get(2).region().centerZ());
    }

    @Test
    void rejectsWallsBeyondSafeRouteAllocationLimit() {
        MapWallPlanner planner = new MapWallPlanner();

        assertThrows(IllegalArgumentException.class, () -> planner.createProject(
                "too-large", "local", "minecraft:overworld", 0, 65, 64, 0, 0, RunMode.MANUAL
        ));
    }
}
