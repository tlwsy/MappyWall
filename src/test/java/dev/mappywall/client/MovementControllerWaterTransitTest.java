package dev.mappywall.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mappywall.core.BoatAcquisitionPolicy.Phase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class MovementControllerWaterTransitTest {
    @Test
    void mountedSubmergedSwimWaypointUsesResolvedSurfaceAndHorizontalTolerance() {
        assertTrue(MovementController.isCompatibleResolvedBoatSurface(
                OptionalInt.of(64), OptionalInt.of(64), OptionalInt.of(64)));
        assertFalse(MovementController.isCompatibleResolvedBoatSurface(
                OptionalInt.of(64), OptionalInt.of(65), OptionalInt.of(64)));
        assertFalse(MovementController.isCompatibleResolvedBoatSurface(
                OptionalInt.of(64), OptionalInt.of(64), OptionalInt.empty()));
        assertTrue(MovementController.isResolvedBoatSwimWaypointComplete(0.42, true, true));
        assertFalse(MovementController.isResolvedBoatSwimWaypointComplete(0.4201, true, true));
        assertFalse(MovementController.isResolvedBoatSwimWaypointComplete(0.42, false, true));
        assertFalse(MovementController.isResolvedBoatSwimWaypointComplete(0.42, true, false));

        assertFalse(MovementController.isWaypointComplete(
                LocalPathPlanner.StepAction.SWIM,
                0.42,
                65.0,
                65.0,
                60,
                false,
                false,
                true
        ));
    }

    @Test
    void compatibleReplanRequiresSameOrAdjacentResolvedAnchorInTheSameRun() {
        BlockPos previous = new BlockPos(0, 60, 0);
        assertTrue(MovementController.canPreserveWaterRunAcrossReplan(
                previous, previous, OptionalInt.of(64), OptionalInt.of(64)));
        assertTrue(MovementController.canPreserveWaterRunAcrossReplan(
                previous, new BlockPos(1, 60, 0), OptionalInt.of(64), OptionalInt.of(64)));
        assertFalse(MovementController.canPreserveWaterRunAcrossReplan(
                previous, new BlockPos(1, 60, 0), OptionalInt.of(64), OptionalInt.empty()));
        assertFalse(MovementController.canPreserveWaterRunAcrossReplan(
                previous, new BlockPos(10, 60, 0), OptionalInt.of(64), OptionalInt.of(64)));
        assertFalse(MovementController.canPreserveWaterRunAcrossReplan(
                previous, new BlockPos(1, 60, 0), OptionalInt.of(64), OptionalInt.of(65)));

        assertTrue(MovementController.shouldCountCompletedWaterEdge(
                OptionalInt.of(64), 64, false));
        assertFalse(MovementController.shouldCountCompletedWaterEdge(
                OptionalInt.of(64), 65, false));
        assertTrue(MovementController.shouldCountCompletedWaterEdge(
                OptionalInt.empty(), 64, true));
        assertFalse(MovementController.shouldCountCompletedWaterEdge(
                OptionalInt.empty(), 64, false));
    }

    @Test
    void controllerUsesAcceptedPreviewAndPreservesWaterPrefixAcrossOrdinaryReplan()
            throws IOException {
        String source = controllerSource();
        assertTrue(source.contains("nextWaypoint(client, player)"));
        assertTrue(source.contains("recordCompletedSwimStep(client, player, step)"));
        assertTrue(source.contains("pathSegments.previewStepSnapshot()"));
        assertTrue(source.contains("waterTransitPolicy.observe("));
        assertTrue(source.contains("waterRouteEvidenceAdapter.resolveBoatableSurface("));

        String next = methodSource(
                source,
                "private LocalPathPlanner.PathStep nextWaypoint(",
                "private void recordCompletedSwimStep("
        );
        int refresh = next.indexOf("refreshWaterEvidence(client, player)");
        int reached = next.indexOf("isAtWaypoint(client, player, step)");
        int advance = next.indexOf("boolean advanced = advancePathStep()");
        int record = next.indexOf("recordCompletedSwimStep(client, player, step)");
        assertTrue(refresh >= 0 && refresh < reached);
        assertTrue(reached < advance && advance < record);
        assertEquals(1, occurrences(source, "refreshWaterEvidence(client, player)"));

        String swim = methodSource(
                source,
                "private MovementResult swimOrBoat(",
                "private MovementResult acquireBoatOrSwim("
        );
        assertFalse(swim.contains("refreshWaterEvidence(client, player)"));
        assertTrue(swim.contains("currentWaterTravelDecision"));
        int acquire = swim.indexOf("ACQUIRE_BOAT");
        assertTrue(acquire >= 0);
        assertTrue(acquire < swim.indexOf("acquireBoatOrSwim("));
        assertFalse(swim.contains("tryBoardNearbyBoat("));
        assertFalse(swim.contains("tryPlaceBoat("));
        assertTrue(swim.contains("CONTINUE_RIDING"));
        assertTrue(swim.contains("driveBoatToward("));
        assertTrue(swim.contains("yield swimToward("));

        String replan = methodSource(
                source,
                "private void forceLocalReplan()",
                "private double squaredHorizontalDistance("
        );
        assertFalse(replan.contains("resetWaterTransit()"));
        assertFalse(replan.contains("waterTransitPolicy.reset()"));
        assertTrue(replan.contains("waterRunAnchor = null"));
        assertTrue(replan.contains("waterReplanContinuityPending"));
        assertTrue(replan.contains("waterReplanAnchor"));
        assertTrue(replan.contains(
                "currentWaterEvidence = WaterRouteEvidenceAdapter.Evidence.none()"));

        String targetChange = methodSource(
                source,
                "private void handleNavigationTargetChange(",
                "private void advancePendingCapture("
        );
        String release = methodSource(
                source,
                "public void release(Minecraft client)",
                "public void hardReset("
        );
        String reset = methodSource(
                source,
                "private void resetProgress()",
                "private void resetBreakBudgetIfTargetChanged("
        );
        String recoveryReset = methodSource(
                source,
                "private void clearPlanningForRecovery()",
                "static boolean shouldBeginVehicleDismountAtArrival("
        );
        assertTrue(targetChange.contains("resetWaterTransit()"));
        assertTrue(release.contains("resetWaterTransit()"));
        assertTrue(reset.contains("resetWaterTransit()"));
        assertTrue(recoveryReset.contains("resetWaterTransit()"));

        String configUpdate = methodSource(
                source,
                "public void setAggressiveConfig(",
                "public MovementResult tick("
        );
        assertTrue(configUpdate.contains("minimumBoatDistanceBlocks()"));
        assertTrue(configUpdate.contains("automationStyle == AutomationStyle.AGGRESSIVE"));
        assertTrue(configUpdate.contains("resetWaterTransit()"));

        String tick = methodSource(
                source,
                "public MovementResult tick(",
                "private void handleNavigationTargetChange("
        );
        assertTrue(tick.contains("updateAutomationStyle(save.project().automationStyle())"));
        String styleUpdate = methodSource(
                source,
                "private void updateAutomationStyle(",
                "private AutoNavigationConfig navigationConfig("
        );
        assertTrue(styleUpdate.contains("automationStyle != requestedStyle"));
        assertTrue(styleUpdate.contains("resetWaterTransit()"));
        assertTrue(styleUpdate.contains("forceLocalReplan()"));

        String completion = methodSource(
                source,
                "private boolean isAtWaypoint(",
                "static boolean evaluateWaypointCompletion("
        );
        assertTrue(compact(completion).contains(
                "if(step.action()==LocalPathPlanner.StepAction.SWIM&&boat!=null){"
                        + "returnisResolvedBoatSwimWaypointComplete("));
    }

    @Test
    void controllerConfirmsPlacementAndRoutesOnlyEligibleWaterToAcquisition()
            throws IOException {
        String source = Files.readString(Path.of(
                "src", "client", "java", "dev", "mappywall", "client",
                "MovementController.java"));
        String compactSource = source.replaceAll("\\s+", "");
        assertTrue(source.contains("InteractionResult placementResult"));
        assertTrue(source.contains("placementResult.consumesAction()"));
        assertTrue(source.contains("captureLoadedBoatIds("));
        assertTrue(compactSource.contains(
                "isConfirmedPlacementBoatEligible(boat.getId(),boatPlacementBaseline"));
        assertTrue(source.contains("client.level.getEntity(selectedBoatId)"));
        assertTrue(source.contains("pathSegments.previewStepSnapshot()"));
        assertTrue(source.contains("findRouteAdjacentEligibleBoat("));
        assertTrue(source.contains("reachableBoatFromCandidate("));
        assertTrue(source.contains("acceptedWaterCorridorSurfaces("));
        assertTrue(source.contains("acceptedApproachSurfaceForBoat("));
        assertTrue(source.contains("boatAcquisitionPolicy.selectedBoatId()"));
        assertTrue(source.contains("swimTowardBoatSurface("));
        assertTrue(source.contains("madeBoatSurfaceVerticalProgress("));
        assertTrue(source.contains("trackStep(waypoint, continuingBoatSurfaceApproach)"));
        assertTrue(compactSource.contains("if(!suspendWaypointTimeout){activeStepTicks++;}"));
        assertTrue(compactSource.contains(
                "returnsuspendWaypointTimeout||activeStepTicks<=STUCK_TICKS_LIMIT*2;"));
        assertTrue(source.contains("!surfaceApproachActive"));
        assertFalse(source.contains("bestBoatWaterPos("));
        assertFalse(source.contains("isSurfaceWaterRoute("));
        assertFalse(source.contains("BOAT_COOLDOWN_TICKS"));
        assertFalse(source.contains("boatCooldown"));

        String swim = methodSource(
                source,
                "private MovementResult swimOrBoat(",
                "private MovementResult swimToward("
        );
        String compactSwim = swim.replaceAll("\\s+", "");
        assertTrue(compactSwim.contains("caseSWIM->"));
        assertTrue(compactSwim.contains("resetBoatAcquisition();"));
        assertTrue(compactSwim.contains("yieldswimToward("));
        assertTrue(compactSwim.contains("caseACQUIRE_BOAT->"));
        assertTrue(compactSwim.contains("acquireBoatOrSwim("));
        assertTrue(compactSource.contains(
                "selectOrMoveToHotbar(client,player,slot);boatAcquisitionPolicy.selectionRequested();"));
    }

    @Test
    void placementConfirmationRequiresANewIdWithinThreeBlocks() {
        Set<Integer> baseline = Set.of(7, 11);
        assertFalse(MovementController.isNewPlacementBoat(7, baseline, 1.0));
        assertFalse(MovementController.isNewPlacementBoat(12, baseline, 9.0001));
        assertFalse(MovementController.isNewPlacementBoat(0, baseline, 1.0));
        assertFalse(MovementController.isNewPlacementBoat(-1, baseline, 1.0));
        assertTrue(MovementController.isNewPlacementBoat(12, baseline, 9.0));
    }

    @Test
    void normalPlacementWaitsForBothYawAndPitchAlignment() throws IOException {
        String source = controllerSource();
        String placement = methodSource(
                source,
                "private Optional<InteractionResult> useBoatItemAtWater(",
                "private float[] lookAngles("
        );
        String compactPlacement = compact(placement);
        assertTrue(compactPlacement.contains("floatlookError=face("));
        assertTrue(compactPlacement.contains(
                "if(Math.max(yawError,lookError)>SPRINT_ALIGNMENT_DEGREES){"
                        + "returnOptional.empty();}"));
    }

    @Test
    void routeBoatOrderingPrefersReachableCandidatesThenDistance() {
        assertTrue(MovementController.isPreferredRouteBoatCandidate(
                true, 16.0, false, 1.0));
        assertFalse(MovementController.isPreferredRouteBoatCandidate(
                false, 1.0, true, 16.0));
        assertTrue(MovementController.isPreferredRouteBoatCandidate(
                true, 4.0, true, 9.0));
        assertTrue(MovementController.isPreferredRouteBoatCandidate(
                false, 4.0, false, 9.0));
    }

    @Test
    void stalledUnreachableRouteBoatUsesBoundedCandidateBackoff() throws IOException {
        assertEquals(1, MovementController.nextRouteBoatApproachStallTicks(
                41, 0, true, false, 89));
        assertEquals(90, MovementController.nextRouteBoatApproachStallTicks(
                41, 41, true, false, 89));
        assertEquals(0, MovementController.nextRouteBoatApproachStallTicks(
                41, 41, true, true, 89));
        assertEquals(0, MovementController.nextRouteBoatApproachStallTicks(
                41, 41, false, false, 89));

        assertFalse(MovementController.shouldBackoffRouteBoat(41, true, true, 90, 8));
        assertFalse(MovementController.shouldBackoffRouteBoat(41, true, false, 89, 7));
        assertTrue(MovementController.shouldBackoffRouteBoat(41, true, false, 90, 0));
        assertFalse(MovementController.shouldBackoffRouteBoat(0, true, false, 90, 8));

        assertFalse(MovementController.isRouteBoatCandidateAvailable(41, 41, 40));
        assertTrue(MovementController.isRouteBoatCandidateAvailable(42, 41, 40));
        assertTrue(MovementController.isRouteBoatCandidateAvailable(41, 41, 0));

        String source = controllerSource();
        assertTrue(source.contains("BoatAcquisitionPolicy.PLACEMENT_RETRY_BACKOFF_TICKS"));
        assertTrue(compact(source).contains("isRouteBoatCandidateAvailable(boat.getId(),"));
        assertTrue(source.contains("boatSurfaceApproachCandidateId"));
        assertTrue(source.contains("startRouteBoatBackoff("));
    }

    @Test
    void collisionBlockedRouteBoatUsesTheSameCandidateBackoff() {
        assertTrue(MovementController.shouldBackoffRouteBoat(
                41, true, false, 0, 8));
        assertFalse(MovementController.shouldBackoffRouteBoat(
                41, true, true, 90, 8));
        assertFalse(MovementController.shouldBackoffRouteBoat(
                41, true, false, 0, 7));
    }

    @Test
    void onlyAReachedConfirmedSurfaceHoldIsStableDuringTransactionFreeze() throws IOException {
        BlockPos surface = new BlockPos(0, 64, 0);
        assertTrue(MovementController.isStableBoatSurfaceHold(
                surface, 0, 0.42, 0.35));
        assertFalse(MovementController.isStableBoatSurfaceHold(
                surface, 41, 0.0, 0.0));
        assertFalse(MovementController.isStableBoatSurfaceHold(
                surface, 0, 0.4201, 0.0));
        assertFalse(MovementController.isStableBoatSurfaceHold(
                surface, 0, 0.0, 0.3501));
        assertFalse(MovementController.isStableBoatSurfaceHold(
                null, 0, 0.0, 0.0));

        String progress = methodSource(
                controllerSource(),
                "private void updateProgress(",
                "private boolean isMovementAction("
        );
        String compactProgress = compact(progress);
        assertTrue(compactProgress.contains(
                "booleanstableSurfaceHold=isStableBoatSurfaceHold("));
        assertTrue(compactProgress.contains("if(madeProgress||stableSurfaceHold){"));
        assertTrue(compactProgress.contains(
                "if(!stableSurfaceHold&&movementAction&&player.horizontalCollision"));
    }

    @Test
    void reachedReachableRouteBoatWaitsThroughLongGuiDeferralWithoutBackoff()
            throws IOException {
        BlockPos surface = new BlockPos(0, 64, 0);
        boolean stableHold = MovementController.isStableBoatSurfaceHold(
                surface, 41, true, true, 0.42, 0.35, false);
        assertTrue(stableHold);

        int previousCandidateId = 0;
        int stalledTicks = 0;
        for (int tick = 0; tick < 1_081; tick++) {
            stalledTicks = MovementController.nextRouteBoatApproachStallTicks(
                    41, previousCandidateId, true, stableHold, stalledTicks);
            assertFalse(MovementController.shouldBackoffRouteBoat(
                    41, true, stableHold, stalledTicks, 0), "GUI tick " + tick);
            previousCandidateId = 41;
        }
        assertEquals(0, stalledTicks);

        String progress = methodSource(
                controllerSource(),
                "private void updateProgress(",
                "private boolean isMovementAction("
        );
        String compactProgress = compact(progress);
        assertTrue(compactProgress.contains(
                "boatSurfaceCandidatePhysicallyReachable,"));
        assertTrue(compactProgress.contains("boatSurfaceInteractionDeferredByGui,"));
        assertTrue(compactProgress.contains("madeProgress||stableSurfaceHold"));
    }

    @Test
    void guiDeferralDoesNotHideUnreachedUnreachableOrCollisionBlockedBoat() {
        BlockPos surface = new BlockPos(0, 64, 0);
        assertFalse(MovementController.isStableBoatSurfaceHold(
                surface, 41, true, true, 0.4201, 0.35, false));
        assertFalse(MovementController.isStableBoatSurfaceHold(
                surface, 41, false, true, 0.42, 0.35, false));
        assertFalse(MovementController.isStableBoatSurfaceHold(
                surface, 41, true, false, 0.42, 0.35, false));
        assertFalse(MovementController.isStableBoatSurfaceHold(
                surface, 41, true, true, 0.42, 0.35, true));
        assertFalse(MovementController.isStableBoatSurfaceHold(
                null, 41, true, true, 0.0, 0.0, false));

        int previousCandidateId = 0;
        int stalledTicks = 0;
        for (int tick = 0; tick < 1_081; tick++) {
            stalledTicks = MovementController.nextRouteBoatApproachStallTicks(
                    41, previousCandidateId, true, false, stalledTicks);
            previousCandidateId = 41;
        }
        assertTrue(stalledTicks >= 90);
        assertTrue(MovementController.shouldBackoffRouteBoat(
                41, true, false, stalledTicks, 0));
        assertTrue(MovementController.shouldBackoffRouteBoat(
                41, true, false, 0, 8));
    }

    @Test
    void closingGuiRestoresAcceptedRouteBoatPriorityBeforeInventoryBoat()
            throws IOException {
        dev.mappywall.core.BoatAcquisitionPolicy policy =
                new dev.mappywall.core.BoatAcquisitionPolicy();
        OptionalInt physicallyReachableBoat = OptionalInt.of(41);
        for (int tick = 0; tick < 1_081; tick++) {
            OptionalInt interactionBoat = MovementController.boatAvailableForInteraction(
                    false, physicallyReachableBoat);
            dev.mappywall.core.BoatAcquisitionPolicy.Decision decision = policy.tick(
                    new dev.mappywall.core.BoatAcquisitionPolicy.Observation(
                            true, false, false, true, true, true, true,
                            interactionBoat, OptionalInt.empty(), false));
            assertEquals(dev.mappywall.core.BoatAcquisitionPolicy.Action.NONE,
                    decision.action());
            assertEquals(Phase.IDLE, policy.phase());
        }

        OptionalInt interactionBoat = MovementController.boatAvailableForInteraction(
                true, physicallyReachableBoat);
        dev.mappywall.core.BoatAcquisitionPolicy.Decision decision = policy.tick(
                new dev.mappywall.core.BoatAcquisitionPolicy.Observation(
                        true, true, false, true, true, true, true,
                        interactionBoat, OptionalInt.empty(), false));
        assertEquals(dev.mappywall.core.BoatAcquisitionPolicy.Action.BOARD_SELECTED_BOAT,
                decision.action());
        assertEquals(41, decision.boatEntityId().orElseThrow());

        String acquisition = methodSource(
                controllerSource(),
                "private MovementResult acquireBoatOrSwim(",
                "static boolean shouldHoldBoatAcquisitionSurface("
        );
        String compactAcquisition = compact(acquisition);
        assertTrue(compactAcquisition.contains(
                "OptionalIntphysicallyReachableBoat=reachableBoatFromCandidate("));
        assertTrue(compactAcquisition.contains(
                "OptionalIntnearbyBoat=boatAvailableForInteraction("
                        + "transactionAvailable,physicallyReachableBoat);"));
        assertTrue(compactAcquisition.contains(
                "boatSurfaceCandidatePhysicallyReachable="));
        assertTrue(compactAcquisition.contains(
                "boatSurfaceInteractionDeferredByGui=client.screen!=null;"));
    }

    @Test
    void surfaceApproachCandidateMatchesTheSelectedHoldSource() throws IOException {
        assertEquals(0, MovementController.surfaceApproachCandidateId(
                true, OptionalInt.of(41), true, OptionalInt.of(42), true));
        assertEquals(41, MovementController.surfaceApproachCandidateId(
                false, OptionalInt.of(41), true, OptionalInt.of(42), true));
        assertEquals(42, MovementController.surfaceApproachCandidateId(
                false, OptionalInt.of(41), false, OptionalInt.of(42), true));
        assertEquals(0, MovementController.surfaceApproachCandidateId(
                false, OptionalInt.empty(), false, OptionalInt.empty(), false));

        String acquisition = methodSource(
                controllerSource(),
                "private MovementResult acquireBoatOrSwim(",
                "static boolean shouldHoldBoatAcquisitionSurface("
        );
        int hold = acquisition.indexOf("Optional<BlockPos> holdSurface");
        int pending = acquisition.indexOf("pendingBoatPlacementSurface != null", hold);
        int selected = acquisition.indexOf("selectedBoatSurface.isPresent()", hold);
        int route = acquisition.indexOf("routeBoatSurface.isPresent()", hold);
        assertTrue(hold >= 0 && hold < selected);
        assertTrue(selected < pending && pending < route);
        assertTrue(acquisition.contains("surfaceApproachCandidateId("));
    }

    @Test
    void selectedBoatMustRemainEligibleForItsAcquisitionPath() throws IOException {
        Set<Integer> baseline = Set.of(7, 11);
        assertTrue(MovementController.isConfirmedPlacementBoatEligible(
                12, baseline, 9.0,
                OptionalInt.of(64), OptionalInt.of(64), OptionalInt.of(64)));
        assertFalse(MovementController.isConfirmedPlacementBoatEligible(
                7, baseline, 1.0,
                OptionalInt.of(64), OptionalInt.of(64), OptionalInt.of(64)));
        assertFalse(MovementController.isConfirmedPlacementBoatEligible(
                12, baseline, 1.0,
                OptionalInt.of(64), OptionalInt.of(65), OptionalInt.of(64)));

        String source = controllerSource();
        String confirmation = methodSource(
                source,
                "private OptionalInt findNewPlacementBoat(",
                "private boolean selectedBoatIsPresent("
        );
        assertTrue(compact(confirmation).contains(
                "isConfirmedPlacementBoatEligible(boat.getId(),boatPlacementBaseline"));
        String selected = methodSource(
                source,
                "private boolean selectedBoatRemainsEligible(",
                "private MovementResult swimOrBoat("
        );
        assertTrue(selected.contains("acceptedApproachSurfaceForBoat(client, boat)"));
        assertTrue(selected.contains("isConfirmedPlacementBoatEligible("));
        String interaction = methodSource(
                source,
                "private boolean interactWithBoat(",
                "private Optional<BlockPos> reachableBoatPlacementSurface("
        );
        assertTrue(interaction.contains("selectedBoatRemainsEligible(client, boat)"));
    }

    @Test
    void onlyAnAcceptedCorridorColumnCanDeferInventoryPlacement() {
        List<BlockPos> accepted = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0)
        );
        assertTrue(MovementController.isAcceptedBoatCorridorColumn(
                new BlockPos(1, 64, 0), accepted));
        assertFalse(MovementController.isAcceptedBoatCorridorColumn(
                new BlockPos(1, 64, 1), accepted));
        assertFalse(MovementController.isAcceptedBoatCorridorColumn(
                new BlockPos(1, 65, 0), accepted));
    }

    @Test
    void deepResolvedSurfaceIsApproachedWithoutTreatingFallbackAsAcquisition() {
        assertTrue(MovementController.shouldHoldBoatAcquisitionSurface(
                Phase.IDLE, true, true));
        assertTrue(MovementController.shouldHoldBoatAcquisitionSurface(
                Phase.AWAITING_ENTITY, false, true));
        assertFalse(MovementController.shouldHoldBoatAcquisitionSurface(
                Phase.IDLE, false, true));
        assertFalse(MovementController.shouldHoldBoatAcquisitionSurface(
                Phase.FALLBACK, true, true));
        assertFalse(MovementController.shouldHoldBoatAcquisitionSurface(
                Phase.AWAITING_ENTITY, true, false));
    }

    @Test
    void verticalSurfaceProgressPreventsFalseHorizontalStall() {
        BlockPos surface = new BlockPos(0, 64, 0);
        assertTrue(MovementController.madeBoatSurfaceVerticalProgress(
                surface, surface, 11.92, 12.0));
        assertFalse(MovementController.madeBoatSurfaceVerticalProgress(
                surface, null, 12.0, Double.MAX_VALUE));
        assertFalse(MovementController.madeBoatSurfaceVerticalProgress(
                surface, surface, 12.0, 12.0));

        double best = MovementController.nextBoatSurfaceBestDistance(
                surface, null, 12.0, Double.MAX_VALUE);
        assertEquals(12.0, best);
        best = MovementController.nextBoatSurfaceBestDistance(
                surface, surface, 11.92, best);
        assertEquals(11.92, best);
        assertFalse(MovementController.madeBoatSurfaceVerticalProgress(
                surface, surface, 11.95, best));
        assertEquals(11.92, MovementController.nextBoatSurfaceBestDistance(
                surface, surface, 11.95, best));
    }

    private static String controllerSource() throws IOException {
        return Files.readString(Path.of(
                "src", "client", "java", "dev", "mappywall", "client",
                "MovementController.java"));
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

}
