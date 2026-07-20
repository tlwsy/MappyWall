package dev.mappywall.core;

import static dev.mappywall.core.WaterTransitPolicy.TravelDecision.ACQUIRE_BOAT;
import static dev.mappywall.core.WaterTransitPolicy.TravelDecision.CONTINUE_RIDING;
import static dev.mappywall.core.WaterTransitPolicy.TravelDecision.SWIM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class WaterTransitPolicyTest {
    @Test
    void requiresTwelveConfirmedBlocksAtTheDefaultBoundary() {
        WaterTransitPolicy policy = new WaterTransitPolicy();
        assertEquals(SWIM, policy.observe(run(64, 11.999, false)));
        assertEquals(ACQUIRE_BOAT, policy.observe(run(64, 12.0, false)));
    }

    @Test
    void completedPrefixCombinesWithLaterRollingEvidenceExactlyOnce() {
        WaterTransitPolicy policy = new WaterTransitPolicy();
        assertEquals(SWIM, policy.observe(run(64, 8.0, false)));
        for (int edge = 0; edge < 4; edge++) {
            policy.recordCompletedEdge(64, 1.0);
        }
        assertEquals(4.0, policy.completedDistanceBlocks(), 1.0e-9);
        assertEquals(ACQUIRE_BOAT, policy.observe(run(64, 8.0, false)));
        assertEquals(4.0, policy.completedDistanceBlocks(), 1.0e-9);
    }

    @Test
    void diagonalDistanceUsesGeometricLength() {
        WaterTransitPolicy policy = new WaterTransitPolicy();
        policy.observe(run(64, 0.0, false));
        policy.recordCompletedEdge(64, Math.sqrt(2.0));
        assertEquals(Math.sqrt(2.0), policy.completedDistanceBlocks(), 1.0e-9);
    }

    @Test
    void unobservedOrDifferentSurfaceDoesNotCountTheBoundaryEdge() {
        WaterTransitPolicy policy = new WaterTransitPolicy();
        policy.recordCompletedEdge(64, 1.0);
        assertEquals(0.0, policy.completedDistanceBlocks(), 1.0e-9);
        policy.observe(run(64, 0.0, false));
        policy.recordCompletedEdge(64, 1.0);
        policy.recordCompletedEdge(65, 1.0);
        assertEquals(0.0, policy.completedDistanceBlocks(), 1.0e-9);
        assertEquals(65, policy.surfaceY().orElseThrow());
    }

    @Test
    void eligibilityLatchesUntilTheWaterRunEnds() {
        WaterTransitPolicy policy = new WaterTransitPolicy();
        assertEquals(ACQUIRE_BOAT, policy.observe(run(64, 12.0, false)));
        assertEquals(ACQUIRE_BOAT, policy.observe(run(64, 1.0, false)));
        assertTrue(policy.acquisitionLatched());
        policy.leaveWaterRun();
        assertFalse(policy.acquisitionLatched());
        assertEquals(0.0, policy.completedDistanceBlocks(), 1.0e-9);
    }

    @Test
    void compatiblePlanningGapDoesNotEraseCompletedDistance() {
        WaterTransitPolicy policy = new WaterTransitPolicy();
        policy.observe(run(64, 4.0, false));
        policy.recordCompletedEdge(64, 3.0);
        // A same-target planning gap makes no policy call.
        assertEquals(3.0, policy.completedDistanceBlocks(), 1.0e-9);
        assertEquals(SWIM, policy.observe(run(64, 8.0, false)));
    }

    @Test
    void incompatibleSurfaceStartsASeparateRun() {
        WaterTransitPolicy policy = new WaterTransitPolicy();
        policy.observe(run(64, 12.0, false));
        policy.recordCompletedEdge(64, 2.0);
        assertEquals(SWIM, policy.observe(run(65, 3.0, false)));
        assertEquals(0.0, policy.completedDistanceBlocks(), 1.0e-9);
        assertFalse(policy.acquisitionLatched());
    }

    @Test
    void ridingBoatContinuesWithoutReapplyingTheAcquisitionThreshold() {
        WaterTransitPolicy policy = new WaterTransitPolicy();
        assertEquals(CONTINUE_RIDING, policy.observe(run(64, 1.0, true)));
    }

    @Test
    void observationsRejectInvalidNumbersAndThresholds() {
        assertThrows(IllegalArgumentException.class, () -> run(64, Double.NaN, false));
        assertThrows(IllegalArgumentException.class, () -> new WaterTransitPolicy.RunObservation(
                OptionalInt.of(64), 1.0, 0, false));
        WaterTransitPolicy policy = new WaterTransitPolicy();
        assertThrows(IllegalArgumentException.class, () -> policy.recordCompletedEdge(64, -1.0));
    }

    private static WaterTransitPolicy.RunObservation run(int surfaceY, double future, boolean riding) {
        return new WaterTransitPolicy.RunObservation(
                OptionalInt.of(surfaceY),
                future,
                WaterTransitPolicy.DEFAULT_MINIMUM_BOAT_DISTANCE_BLOCKS,
                riding
        );
    }
}
