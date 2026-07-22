package dev.mappywall.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;
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
                "private boolean isSurfaceWaterRoute("
        );
        assertFalse(swim.contains("refreshWaterEvidence(client, player)"));
        assertTrue(swim.contains("currentWaterTravelDecision"));
        int acquire = swim.indexOf("ACQUIRE_BOAT");
        assertTrue(acquire >= 0);
        assertTrue(acquire < swim.indexOf("tryBoardNearbyBoat("));
        assertTrue(acquire < swim.indexOf("tryPlaceBoat("));
        assertTrue(swim.contains("CONTINUE_RIDING"));
        assertTrue(swim.contains("driveBoatToward("));
        assertTrue(swim.contains("return swimToward("));

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
