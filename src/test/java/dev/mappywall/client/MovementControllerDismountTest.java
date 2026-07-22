package dev.mappywall.client;

import static dev.mappywall.core.BoatDismountRecovery.Action.COMPLETE_REPLAN;
import static dev.mappywall.core.BoatDismountRecovery.Action.FAILED;
import static dev.mappywall.core.BoatDismountRecovery.Action.HOLD;
import static dev.mappywall.core.BoatDismountRecovery.Action.NONE;
import static dev.mappywall.core.BoatDismountRecovery.Action.PULSE_JUMP;
import static dev.mappywall.core.BoatDismountRecovery.Action.REQUEST_DISMOUNT;
import static dev.mappywall.core.BoatDismountRecovery.Action.STEER_EGRESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mappywall.core.BoatDismountRecovery.Action;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.entity.player.Input;
import org.junit.jupiter.api.Test;

class MovementControllerDismountTest {
    @Test
    void beginningClearsPlanningOnceAndReturnsWithoutFreshPlan() {
        MovementController.DismountLifecycleEffects effects =
                MovementController.dismountLifecycleEffects(true, NONE, true);

        assertTrue(effects.returnImmediately());
        assertTrue(effects.clearPlanning());
        assertFalse(effects.freshReplan());
        assertFalse(effects.pauseStuck());
    }

    @Test
    void everyRecoveryOutcomeReturnsBeforeOrdinaryWork() {
        List<Action> outcomes = List.of(
                REQUEST_DISMOUNT,
                HOLD,
                STEER_EGRESS,
                PULSE_JUMP,
                COMPLETE_REPLAN,
                FAILED
        );

        for (Action outcome : outcomes) {
            assertTrue(
                    MovementController.dismountLifecycleEffects(false, outcome, true)
                            .returnImmediately(),
                    outcome.name()
            );
        }
    }

    @Test
    void requestAndPassengerHoldKeepShiftWithoutNeutralizingIt() {
        assertEquals(
                MovementController.DismountInputMode.REQUEST_SHIFT,
                MovementController.dismountLifecycleEffects(false, REQUEST_DISMOUNT, true).inputMode()
        );
        assertEquals(
                MovementController.DismountInputMode.KEEP_SHIFT,
                MovementController.dismountLifecycleEffects(false, HOLD, true).inputMode()
        );
        assertEquals(
                MovementController.DismountInputMode.NEUTRAL,
                MovementController.dismountLifecycleEffects(false, HOLD, false).inputMode()
        );
    }

    @Test
    void completeRequestsOneFreshPlanAndFailureOnlyPauses() {
        MovementController.DismountLifecycleEffects complete =
                MovementController.dismountLifecycleEffects(false, COMPLETE_REPLAN, false);
        MovementController.DismountLifecycleEffects failed =
                MovementController.dismountLifecycleEffects(false, FAILED, false);

        assertTrue(complete.freshReplan());
        assertFalse(complete.pauseStuck());
        assertFalse(failed.freshReplan());
        assertTrue(failed.pauseStuck());
    }

    @Test
    void egressActionsUseOnlyTheirBoundedMotion() {
        assertEquals(
                MovementController.DismountMotion.STEER,
                MovementController.dismountLifecycleEffects(false, STEER_EGRESS, false).motion()
        );
        assertEquals(
                MovementController.DismountMotion.JUMP,
                MovementController.dismountLifecycleEffects(false, PULSE_JUMP, false).motion()
        );
        assertEquals(
                MovementController.DismountMotion.NONE,
                MovementController.dismountLifecycleEffects(false, HOLD, false).motion()
        );
    }

    @Test
    void boardingSeamRejectsOnlyIdsSelectedBySuppressionPolicy() {
        assertFalse(MovementController.shouldBoardBoat(41, candidate -> candidate == 41));
        assertTrue(MovementController.shouldBoardBoat(42, candidate -> candidate == 41));
    }

    @Test
    void arrivalAndNonSwimWaypointsDismountAnyVehicleButSwimExitRequiresBoat() {
        assertTrue(MovementController.shouldBeginVehicleDismountAtArrival(true, true));
        assertFalse(MovementController.shouldBeginVehicleDismountAtArrival(true, false));
        assertFalse(MovementController.shouldBeginVehicleDismountAtArrival(false, true));

        // A horse or minecart is still a server-confirmed Shift recovery at a
        // land waypoint; only the SWIM-to-land transition is boat-specific.
        assertTrue(MovementController.shouldBeginVehicleDismountForWaypoint(
                true, true, false, false, false
        ));
        assertTrue(MovementController.shouldBeginVehicleDismountForWaypoint(
                true, true, true, true, false
        ));
        assertFalse(MovementController.shouldBeginVehicleDismountForWaypoint(
                true, true, true, false, false
        ));
        assertFalse(MovementController.shouldBeginVehicleDismountForWaypoint(
                true, true, true, true, true
        ));
        assertFalse(MovementController.shouldBeginVehicleDismountForWaypoint(
                false, true, false, false, false
        ));
        assertFalse(MovementController.shouldBeginVehicleDismountForWaypoint(
                true, false, false, false, false
        ));
    }

    @Test
    void releaseAlwaysClearsLiveInputEvenWhenCachedInputWasNeutral() {
        Input heldShift = new Input(false, false, false, false, false, true, false);

        assertSame(Input.EMPTY, MovementController.releasedPlayerInput(true, heldShift));
        assertSame(Input.EMPTY, MovementController.releasedPlayerInput(false, heldShift));
        assertSame(Input.EMPTY, MovementController.releasedPlayerInput(true, Input.EMPTY));
        assertSame(Input.EMPTY, MovementController.releasedPlayerInput(false, Input.EMPTY));
    }

    @Test
    void terminalDispatchRunsOneReplanForCompleteAndOnlyPausesForFailure() {
        AtomicInteger replans = new AtomicInteger();

        MovementController.DismountTerminalOutcome complete =
                MovementController.applyDismountTerminalEffects(
                        MovementController.dismountLifecycleEffects(false, COMPLETE_REPLAN, false),
                        replans::incrementAndGet
                );
        assertEquals(1, replans.get());
        assertFalse(complete.pauseStuck());

        AtomicInteger failedReplans = new AtomicInteger();
        MovementController.DismountTerminalOutcome failed =
                MovementController.applyDismountTerminalEffects(
                        MovementController.dismountLifecycleEffects(false, FAILED, false),
                        failedReplans::incrementAndGet
                );
        assertEquals(0, failedReplans.get());
        assertTrue(failed.pauseStuck());
    }

    @Test
    void runtimeDecisionCoversTargetsModesAndRecoveryBoundaries() {
        assertDecision(true, false, false, true, true, true, false, false, false, false);
        assertDecision(true, true, false, true, true, true, true, false, true, false);
        assertDecision(true, true, false, true, true, true, false, true, false, true);
        assertDecision(false, false, true, true, true, true, false, false, true, false);
        assertDecision(true, false, false, true, false, true, true, false, false, false);
        assertDecision(false, false, true, true, false, true, true, false, true, false);
        assertDecision(false, false, true, false, false, true, true, true, false, false);
        assertDecision(false, false, true, true, true, false, true, true, false, false);
    }

    @Test
    void controllerSourceKeepsRecoveryBeforePlanningAndNeverDetachesLocally() throws IOException {
        String source = controllerSource();

        assertFalse(source.contains(".stopRiding("));
        assertFalse(source.contains(".removeVehicle("));
        assertFalse(source.contains("teleportTo("));
        assertEquals(1, occurrences(source, "dismountRecovery.tick("));
        assertTrue(source.contains(
                "Boarding suppression advances only on actual AUTO_WALK controller ticks"
        ));

        int targetChange = source.indexOf("handleNavigationTargetChange(target)");
        int wasActive = source.indexOf("boolean recoveryWasActive = dismountRecovery.active()", targetChange);
        int policyTick = source.indexOf("dismountRecovery.tick(", wasActive);
        int activeGate = source.indexOf("if (recoveryWasActive)", policyTick);
        int planningTick = source.indexOf("planningRetry.beginTick()", activeGate);
        assertTrue(targetChange >= 0);
        assertTrue(targetChange < wasActive);
        assertTrue(wasActive < policyTick);
        assertTrue(policyTick < activeGate);
        assertTrue(activeGate < planningTick);
        String activeGateSource = source.substring(activeGate, planningTick);
        assertTrue(compact(activeGateSource).startsWith(
                "if(recoveryWasActive){returnserviceDismountRecovery("
                        + "client,player,target,recoveryAction);}"
        ));

        int elytraMode = source.indexOf("save.project().mode() == RunMode.AUTO_ELYTRA");
        int cancelForMode = source.indexOf("cancelDismountRecoveryForModeChange()", elytraMode);
        int tickElytra = source.indexOf("return tickElytra(client, save, target)", cancelForMode);
        assertTrue(elytraMode < cancelForMode);
        assertTrue(cancelForMode < tickElytra);

        String modeCancel = methodSource(
                source,
                "private void cancelDismountRecoveryForModeChange(",
                "private boolean beginVehicleDismountRecovery("
        );
        assertTrue(modeCancel.contains("dismountRecovery.cancel()"));
        assertTrue(modeCancel.contains("resetDismountEgressProgress(null, null)"));
        assertFalse(modeCancel.contains("dismountRecovery.reset()"));
    }

    @Test
    void controllerSourceCentralizesVehicleBeginsAndLifecycleResetWiring() throws IOException {
        String source = controllerSource();

        assertEquals(2, occurrences(source, "beginVehicleDismountRecovery(client, player)"));
        assertTrue(source.contains("shouldBeginVehicleDismountAtArrival("));
        assertTrue(source.contains("shouldBeginVehicleDismountForWaypoint("));
        assertFalse(source.contains("dismountCooldown"));
        assertTrue(source.contains("dismountRecovery.requestPosition()"));
        assertTrue(source.contains("dismountRecovery.cancel()"));
        assertTrue(source.contains("dismountRecovery.reset()"));
        assertTrue(source.contains("shouldBoardBoat(boat.getId(), dismountRecovery::suppressBoarding)"));

        String arrival = methodSource(
                source,
                "if (arrivedAtNavigationTarget(player, target)) {",
                "tickCooldowns();"
        );
        assertEquals(0, occurrences(arrival, "AbstractBoat"));
        assertEquals(1, occurrences(arrival, "shouldBeginVehicleDismountAtArrival("));
        assertTrue(compact(arrival).contains(
                "shouldBeginVehicleDismountAtArrival("
                        + "player.isPassenger(),player.getVehicle()!=null)"
                        + "&&beginVehicleDismountRecovery(client,player)"
        ));

        String waypointDismount = methodSource(
                source,
                "boolean swimWaypoint = waypoint.action() == LocalPathPlanner.StepAction.SWIM;",
                "if (tryEat(client, player)) {"
        );
        assertEquals(2, occurrences(waypointDismount, "instanceof AbstractBoat"));
        assertEquals(3, occurrences(waypointDismount, "vehicleIsBoat"));
        assertEquals(1, occurrences(waypointDismount, "shouldBeginVehicleDismountForWaypoint("));
        assertTrue(compact(waypointDismount).contains(
                "shouldBeginVehicleDismountForWaypoint("
                        + "player.isPassenger(),vehiclePresent,swimWaypoint,vehicleIsBoat,boatableSwimRoute)"
                        + "&&beginVehicleDismountRecovery(client,player)"
        ));
        assertTrue(compact(waypointDismount).contains(
                "isCompatibleResolvedBoatSurface(waterTransitPolicy.surfaceY(),"
                        + "waypointSurfaceY,boatSurfaceY)"
        ));

        String begin = methodSource(
                source,
                "private boolean beginVehicleDismountRecovery(",
                "private MovementResult serviceDismountRecovery("
        );
        assertEquals(1, occurrences(begin, "clearPlanningForRecovery()"));
        assertTrue(begin.contains("Entity vehicle = player.getVehicle()"));
        assertFalse(begin.contains("instanceof AbstractBoat"));
        assertFalse(begin.contains("forceLocalReplan()"));

        String clearPlanning = methodSource(
                source,
                "private void clearPlanningForRecovery(",
                "static boolean shouldBoardBoat("
        );
        for (String reset : List.of(
                "lastDistance = Double.MAX_VALUE",
                "lastWaypointDistance = Double.MAX_VALUE",
                "lastPlayerPos = Vec3.ZERO",
                "stuckTicks = 0",
                "horizontalCollisionTicks = 0",
                "actionFailures = 0",
                "movementRecoveryFailures = 0",
                "movementSamples.clear()",
                "movementSampleTick = 0"
        )) {
            assertTrue(clearPlanning.contains(reset), reset);
        }
        assertFalse(clearPlanning.contains("planningRetry.forceFreshSnapshot"));

        String keepShift = methodSource(
                source,
                "private void keepDismountShiftLocally(",
                "private Observation observeVehicleDismountRecovery("
        );
        assertFalse(keepShift.contains("releaseMovementKeys("));
        assertFalse(keepShift.contains("sendNeutralInput("));
        assertFalse(keepShift.contains("sendPlayerInput("));

        int nextWaypoint = source.indexOf(
                "LocalPathPlanner.PathStep waypoint = nextWaypoint(client, player)"
        );
        int nonSwimBegin = source.indexOf("beginVehicleDismountRecovery(client, player)", nextWaypoint);
        int eat = source.indexOf("tryEat(client, player)", nonSwimBegin);
        int chunkSafety = source.indexOf("isStepChunkReady(client, waypoint)", eat);
        assertTrue(nextWaypoint < nonSwimBegin);
        assertTrue(nonSwimBegin < eat);
        assertTrue(eat < chunkSafety);

        String releaseKeys = methodSource(
                source,
                "private void releaseMovementKeys(",
                "static Input releasedPlayerInput("
        );
        assertEquals(1, occurrences(releaseKeys, "releasedPlayerInput("));
        assertTrue(compact(releaseKeys).contains(
                "clearVanillaMovementKeys(client);if(client.player!=null){"
                        + "client.player.input.keyPresses=releasedPlayerInput("
                        + "lastDirectInput.equals(DirectInput.NEUTRAL),client.player.input.keyPresses);"
                        + "}sendNeutralInput(client,false);"
        ));
        assertTrue(releaseKeys.indexOf("releasedPlayerInput(") < releaseKeys.indexOf("sendNeutralInput("));

        String release = methodSource(source, "public void release(Minecraft client) {", "public void hardReset(");
        assertTrue(compact(release).startsWith(
                "publicvoidrelease(Minecraftclient){releaseMovementKeys(client);"
        ));
    }

    @Test
    void controllerSourceRevalidatesStandingEgressAndIncludesFormerBoatCollision() throws IOException {
        String source = controllerSource();

        assertTrue(source.contains("getDimensions(Pose.STANDING)"));
        assertTrue(source.contains("makeBoundingBox(Vec3.atBottomCenterOf(feet))"));
        assertTrue(source.contains("getWorldBorder().isWithinBounds(destinationBox)"));
        assertTrue(source.contains("noCollision(player, destinationBox)"));
        assertTrue(source.contains("player.getBoundingBox().inflate(VEHICLE_CONTACT_INFLATION)"));
        assertTrue(source.contains("revalidateDismountEgress"));
        assertTrue(source.contains("resetDismountEgressProgress"));

        String service = methodSource(
                source,
                "private MovementResult serviceDismountRecovery(",
                "private void requestServerDismount("
        );
        assertEquals(2, occurrences(service, "revalidateDismountEgress(client, player, target)"));
        assertTrue(service.contains("prepareDismountMotion(client)"));
        assertEquals(1, occurrences(service, "applyDismountTerminalEffects("));
        assertEquals(1, occurrences(service, "this::forceLocalReplan"));
        assertEquals(0, occurrences(service, "forceLocalReplan();"));
        assertFalse(service.contains("if (effects.freshReplan())"));
        assertTrue(compact(service).contains(
                "if(terminalOutcome.pauseStuck()){returnMovementResult.pause("
                        + "Component.translatable(\"message.mappywall.auto_walk_stuck\"));}"
        ));

        String prepareMotion = methodSource(
                source,
                "private void prepareDismountMotion(",
                "private void requestServerDismount("
        );
        assertFalse(prepareMotion.contains("sendNeutralInput("));
        assertFalse(prepareMotion.contains("releaseMovementKeys("));
        assertFalse(prepareMotion.contains("sendPlayerInput("));

        String revalidation = methodSource(
                source,
                "private BlockPos revalidateDismountEgress(",
                "private boolean isValidDismountEgress("
        );
        assertTrue(revalidation.contains("isValidDismountEgress(client, player, dismountEgress)"));
        assertTrue(revalidation.contains("boatEgressSelector.select("));
        assertTrue(revalidation.contains("!Objects.equals(selected, dismountEgress)"));
        assertTrue(revalidation.contains("resetDismountEgressProgress(selected, player)"));

        String validity = methodSource(
                source,
                "private boolean isValidDismountEgress(",
                "private boolean isStableDismountSupport("
        );
        assertTrue(validity.contains("areLiveBlocksLoaded"));
        assertTrue(validity.contains("isLiveBodyClear"));
        assertTrue(validity.contains("isDangerousLiveBlock"));
        assertTrue(validity.contains("isStableDismountSupport"));
        assertTrue(validity.contains("isSafeDismountWater"));
        assertTrue(validity.contains("isDismountDestinationCollisionFree"));

        String safeWater = methodSource(
                source,
                "private boolean isSafeDismountWater(",
                "private boolean isDismountDestinationCollisionFree("
        );
        assertTrue(safeWater.contains("areLiveBlocksLoaded(client, feet, feet.above(), feet.below())"));
        assertTrue(safeWater.contains("isDangerousLiveBlock(client, feet.below())"));
        assertTrue(revalidation.contains(
                ".filter(candidate -> isValidDismountEgress(client, player, candidate))"
        ));
    }

    @Test
    void runtimeSourceTicksBeforeReachedHandlingDuringRecovery() throws IOException {
        String source = runtimeSource();
        assertTrue(source.contains("boolean passenger = client.player != null && client.player.isPassenger();"));
        assertTrue(source.contains("boolean autoWalk = activeSave.project().mode() == RunMode.AUTO_WALK;"));
        assertTrue(source.contains("movementTarget != null,"));
        assertTrue(source.contains("if (movementDecision.serviceMovement())"));
        assertTrue(source.contains("else if (movementDecision.releaseMovement())"));
        int decision = source.indexOf("movementControlDecision(");
        int movementTick = source.indexOf("movementController.tick(", decision);
        int deferReturn = source.indexOf("if (deferTargetAction)", movementTick);
        int reachedHandling = source.indexOf("handleReachedFillTarget(", deferReturn);
        int mapOpen = source.indexOf("mapOpenController.tryOpenMapInRegion(", deferReturn);

        assertTrue(decision >= 0);
        assertTrue(decision < movementTick);
        assertTrue(movementTick < deferReturn);
        assertTrue(deferReturn < reachedHandling);
        assertTrue(deferReturn < mapOpen);
    }

    private static String controllerSource() throws IOException {
        return Files.readString(sourcePath("MovementController.java"));
    }

    private static String runtimeSource() throws IOException {
        return Files.readString(sourcePath("MappyWallRuntime.java"));
    }

    private static Path sourcePath(String fileName) {
        return Path.of(
                System.getProperty("user.dir"),
                "src",
                "client",
                "java",
                "dev",
                "mappywall",
                "client",
                fileName
        );
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        for (int index = 0; (index = source.indexOf(needle, index)) >= 0; index += needle.length()) {
            count++;
        }
        return count;
    }

    private static String methodSource(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex);
        assertTrue(startIndex >= 0, start);
        assertTrue(endIndex > startIndex, end);
        return source.substring(startIndex, endIndex);
    }

    private static String compact(String source) {
        return source.replaceAll("\\s+", "");
    }

    private static void assertDecision(
            boolean serviceMovement,
            boolean deferTargetAction,
            boolean releaseMovement,
            boolean automatic,
            boolean autoWalk,
            boolean hasMovementTarget,
            boolean passenger,
            boolean recoveringDismount,
            boolean reachedFillTarget,
            boolean stagingMapOpen
    ) {
        assertEquals(
                new MappyWallRuntime.MovementControlDecision(
                        serviceMovement,
                        deferTargetAction,
                        releaseMovement
                ),
                MappyWallRuntime.movementControlDecision(
                        automatic,
                        autoWalk,
                        hasMovementTarget,
                        passenger,
                        recoveringDismount,
                        reachedFillTarget,
                        stagingMapOpen
                )
        );
    }

}
