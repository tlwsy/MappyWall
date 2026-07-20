package dev.mappywall.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

final class NavigationFeetMovementPolicyTest {
    private final NavigationFeetResolver feetResolver = new NavigationFeetResolver();

    @Test
    void groundedOrdinaryWalkDelegatesCollisionResolutionToVanilla() {
        assertTrue(MovementController.shouldDelegateAggressiveWalkCollisionToVanilla(
                LocalPathPlanner.StepAction.WALK,
                true,
                false,
                false,
                false,
                false
        ));
    }

    @ParameterizedTest
    @EnumSource(value = LocalPathPlanner.StepAction.class, names = {
            "JUMP", "DROP", "SWIM", "BREAK", "PLACE"
    })
    void nonWalkActionsKeepDedicatedCollisionHandling(LocalPathPlanner.StepAction action) {
        assertFalse(MovementController.shouldDelegateAggressiveWalkCollisionToVanilla(
                action,
                true,
                false,
                false,
                false,
                false
        ));
    }

    @Test
    void walkOutsideOrdinaryGroundMovementKeepsDedicatedHandling() {
        assertFalse(MovementController.shouldDelegateAggressiveWalkCollisionToVanilla(
                LocalPathPlanner.StepAction.WALK, false, false, false, false, false));
        assertFalse(MovementController.shouldDelegateAggressiveWalkCollisionToVanilla(
                LocalPathPlanner.StepAction.WALK, true, true, false, false, false));
        assertFalse(MovementController.shouldDelegateAggressiveWalkCollisionToVanilla(
                LocalPathPlanner.StepAction.WALK, true, false, true, false, false));
        assertFalse(MovementController.shouldDelegateAggressiveWalkCollisionToVanilla(
                LocalPathPlanner.StepAction.WALK, true, false, false, true, false));
        assertFalse(MovementController.shouldDelegateAggressiveWalkCollisionToVanilla(
                LocalPathPlanner.StepAction.WALK, true, false, false, false, true));
    }

    @Test
    void vanillaCollisionDelegationRejectsNullActions() {
        assertThrows(NullPointerException.class, () ->
                MovementController.shouldDelegateAggressiveWalkCollisionToVanilla(
                        null, true, false, false, false, false));
    }

    @ParameterizedTest
    @ValueSource(doubles = {63.9375, 63.875, 63.5, 63.125})
    void groundedWalkCompletesAgainstLogicalFeetWithoutLargerTolerance(double physicalFeetY) {
        assertTrue(MovementController.isWaypointComplete(
                LocalPathPlanner.StepAction.WALK,
                0.42,
                physicalFeetY,
                64,
                64,
                true,
                false,
                false
        ));
    }

    @Test
    void unsupportedPartialHeightMustRetainPhysicalWaypointYInsteadOfRawFloor() {
        double physicalFeetY = 63.9375;
        double selectedWaypointY = feetResolver.resolveWaypointY(
                4.5,
                physicalFeetY,
                7.5,
                true,
                ignored -> false
        );

        assertTrue(MovementController.isWaypointComplete(
                LocalPathPlanner.StepAction.WALK,
                0.42,
                physicalFeetY,
                selectedWaypointY,
                64,
                true,
                false,
                false
        ));
        assertFalse(MovementController.isWaypointComplete(
                LocalPathPlanner.StepAction.WALK,
                0.42,
                physicalFeetY,
                selectedWaypointY,
                63,
                true,
                false,
                false
        ));
    }

    @Test
    void disabledNormalizationMustRetainPhysicalWaypointYInsteadOfRawFloor() {
        double physicalFeetY = 63.9375;
        double selectedWaypointY = feetResolver.resolveWaypointY(
                4.5,
                physicalFeetY,
                7.5,
                false,
                ignored -> true
        );

        assertTrue(MovementController.isWaypointComplete(
                LocalPathPlanner.StepAction.WALK,
                0.42,
                physicalFeetY,
                selectedWaypointY,
                64,
                true,
                false,
                false
        ));
        assertFalse(MovementController.isWaypointComplete(
                LocalPathPlanner.StepAction.WALK,
                0.42,
                physicalFeetY,
                selectedWaypointY,
                63,
                true,
                false,
                false
        ));
    }

    @ParameterizedTest
    @ValueSource(doubles = {63.9375, 63.875, 63.5, 63.125})
    void groundedJumpAndDropCompleteAgainstLogicalFeet(double physicalFeetY) {
        assertTrue(MovementController.isWaypointComplete(
                LocalPathPlanner.StepAction.JUMP,
                0.52,
                physicalFeetY,
                64,
                64,
                true,
                false,
                false
        ));
        assertTrue(MovementController.isWaypointComplete(
                LocalPathPlanner.StepAction.DROP,
                0.58,
                physicalFeetY,
                64,
                64,
                true,
                false,
                false
        ));
    }

    @ParameterizedTest
    @ValueSource(doubles = {63.9375, 63.875, 63.5, 63.125})
    void airborneFractionalFeetDoNotCompleteGroundedMovement(double physicalFeetY) {
        for (LocalPathPlanner.StepAction action : new LocalPathPlanner.StepAction[] {
                LocalPathPlanner.StepAction.WALK,
                LocalPathPlanner.StepAction.JUMP,
                LocalPathPlanner.StepAction.DROP
        }) {
            assertFalse(MovementController.isWaypointComplete(
                    action,
                    0.0,
                    physicalFeetY,
                    64,
                    64,
                    false,
                    false,
                    false
            ));
        }
    }

    @Test
    void swimRetainsPhysicalYAndItsExistingTolerance() {
        assertTrue(MovementController.isWaypointComplete(
                LocalPathPlanner.StepAction.SWIM,
                0.42,
                65.25,
                80,
                64,
                false,
                true,
                false
        ));
        assertTrue(MovementController.isWaypointComplete(
                LocalPathPlanner.StepAction.SWIM,
                0.42,
                62.75,
                48,
                64,
                false,
                false,
                true
        ));
        assertFalse(MovementController.isWaypointComplete(
                LocalPathPlanner.StepAction.SWIM,
                0.42,
                65.2501,
                64,
                64,
                false,
                true,
                false
        ));
    }

    @Test
    void walkInWaterRetainsPhysicalY() {
        double waypointY = feetResolver.resolveWaypointY(
                0.5,
                64,
                0.5,
                false,
                ignored -> true
        );
        assertTrue(MovementController.isWaypointComplete(
                LocalPathPlanner.StepAction.WALK,
                0.42,
                64,
                waypointY,
                64,
                false,
                true,
                false
        ));
        assertFalse(MovementController.isWaypointComplete(
                LocalPathPlanner.StepAction.WALK,
                0.42,
                65,
                65,
                64,
                false,
                true,
                false
        ));
    }

    @Test
    void jumpAndDropKeepTheirGroundStateAndHorizontalRequirements() {
        assertFalse(MovementController.isWaypointComplete(
                LocalPathPlanner.StepAction.JUMP,
                0.5201,
                64,
                64,
                64,
                true,
                false,
                false
        ));
        assertFalse(MovementController.isWaypointComplete(
                LocalPathPlanner.StepAction.JUMP,
                0.0,
                64,
                64,
                64,
                false,
                true,
                false
        ));
        assertFalse(MovementController.isWaypointComplete(
                LocalPathPlanner.StepAction.DROP,
                0.5801,
                64,
                64,
                64,
                true,
                false,
                false
        ));
        assertTrue(MovementController.isWaypointComplete(
                LocalPathPlanner.StepAction.DROP,
                0.58,
                64,
                64,
                64,
                false,
                true,
                false
        ));
    }

    @ParameterizedTest
    @EnumSource(value = LocalPathPlanner.StepAction.class, names = {"BREAK", "PLACE"})
    void modificationActionsNeverCompleteAsWaypoints(LocalPathPlanner.StepAction action) {
        assertFalse(MovementController.isWaypointComplete(
                action,
                0.0,
                64,
                64,
                64,
                true,
                false,
                false
        ));
    }

    @ParameterizedTest(name = "{0} keeps selected physical waypoint Y")
    @ValueSource(strings = {"passenger", "swimming", "climbing"})
    void normalizationExcludedStatesUsePhysicalWaypointY(String state) {
        double physicalFeetY = 63.9375;
        double selectedWaypointY = feetResolver.resolveWaypointY(
                2.5,
                physicalFeetY,
                3.5,
                false,
                ignored -> true
        );

        assertTrue(MovementController.isWaypointComplete(
                LocalPathPlanner.StepAction.WALK,
                0.42,
                physicalFeetY,
                selectedWaypointY,
                64,
                true,
                false,
                false
        ), state);
        assertFalse(MovementController.isWaypointComplete(
                LocalPathPlanner.StepAction.WALK,
                0.42,
                physicalFeetY,
                selectedWaypointY,
                63,
                true,
                false,
                false
        ), state);
    }

    @Test
    void waypointAdapterResolvesSelectedYExactlyOnceAndFeedsThePolicy() {
        AtomicInteger resolutions = new AtomicInteger();

        boolean complete = MovementController.evaluateWaypointCompletion(
                LocalPathPlanner.StepAction.WALK,
                0.42,
                63.125,
                64,
                true,
                false,
                false,
                () -> {
                    resolutions.incrementAndGet();
                    return 64;
                }
        );

        assertTrue(complete);
        assertEquals(1, resolutions.get());
    }

    @Test
    void projectedPartialSupportUsesResolvedFeetBeforeBodyAndSupportChecks() {
        BlockPos destinationSupport = new BlockPos(11, 63, -4);
        AtomicInteger resolutions = new AtomicInteger();
        AtomicReference<BlockPos> bodyProbe = new AtomicReference<>();
        AtomicReference<BlockPos> supportProbe = new AtomicReference<>();

        boolean safe = MovementController.isProjectedSupportSafe(
                11.75,
                -3.25,
                (x, z) -> {
                    resolutions.incrementAndGet();
                    assertEquals(11.75, x);
                    assertEquals(-3.25, z);
                    return feetResolver.resolve(
                            x,
                            63.9375,
                            z,
                            true,
                            destinationSupport::equals
                    );
                },
                candidate -> {
                    bodyProbe.set(candidate);
                    return candidate.equals(destinationSupport.above());
                },
                support -> {
                    supportProbe.set(support);
                    return support.equals(destinationSupport);
                }
        );

        assertTrue(safe);
        assertEquals(1, resolutions.get());
        assertEquals(destinationSupport.above(), bodyProbe.get());
        assertEquals(destinationSupport, supportProbe.get());
    }

    @Test
    void projectedUnsupportedAirOrWaterStillFailsSafeSupportPolicy() {
        AtomicInteger resolutions = new AtomicInteger();
        AtomicInteger supportChecks = new AtomicInteger();

        boolean safe = MovementController.isProjectedSupportSafe(
                8.5,
                2.5,
                (x, z) -> {
                    resolutions.incrementAndGet();
                    return feetResolver.resolve(
                            x,
                            63.9375,
                            z,
                            true,
                            ignored -> false
                    );
                },
                ignored -> true,
                ignored -> {
                    supportChecks.incrementAndGet();
                    return false;
                }
        );

        assertFalse(safe);
        assertEquals(1, resolutions.get());
        assertEquals(1, supportChecks.get());
    }

    @Test
    void projectedSupportShortCircuitsAfterRejectedResolvedBody() {
        AtomicInteger resolutions = new AtomicInteger();
        AtomicInteger supportChecks = new AtomicInteger();

        boolean safe = MovementController.isProjectedSupportSafe(
                8.5,
                2.5,
                (x, z) -> {
                    resolutions.incrementAndGet();
                    return new BlockPos(8, 64, 2);
                },
                ignored -> false,
                ignored -> {
                    supportChecks.incrementAndGet();
                    return true;
                }
        );

        assertFalse(safe);
        assertEquals(1, resolutions.get());
        assertEquals(0, supportChecks.get());
    }

    @Test
    void liveProjectedSupportCallsiteUsesTheNavigationFeetResolver() throws IOException {
        Path sourcePath = Path.of(
                "src",
                "client",
                "java",
                "dev",
                "mappywall",
                "client",
                "MovementController.java"
        );
        String source = Files.readString(sourcePath);
        int methodStart = source.indexOf("private boolean hasSafeProjectedSupport(");
        int methodEnd = source.indexOf("private void setDirectMovementState", methodStart);
        String methodSource = source.substring(methodStart, methodEnd);

        assertTrue(methodSource.contains("navigationFeetResolver.resolveProjected("));
        assertFalse(methodSource.contains("BlockPos.containing("));
        assertTrue(methodSource.contains("return isProjectedSupportSafe("));
        assertTrue(methodSource.contains("navigationFeetResolver.resolveProjected(player, x, z)"));
    }

    @Test
    void liveWaypointCallsiteDelegatesSelectedResolverYIntoThePolicy() throws IOException {
        Path sourcePath = Path.of(
                "src",
                "client",
                "java",
                "dev",
                "mappywall",
                "client",
                "MovementController.java"
        );
        String source = Files.readString(sourcePath);
        int methodStart = source.indexOf("private boolean isAtWaypoint(");
        int methodEnd = source.indexOf("static boolean evaluateWaypointCompletion", methodStart);
        String methodSource = source.substring(methodStart, methodEnd);

        assertTrue(methodSource.contains("evaluateWaypointCompletion("));
        assertTrue(methodSource.contains("() -> navigationFeetResolver.resolveWaypointY(player)"));
        assertFalse(methodSource.contains("() -> player.getY()"));
        assertFalse(methodSource.contains("navigationFeetResolver.resolve(player).getY()"));
    }
}
