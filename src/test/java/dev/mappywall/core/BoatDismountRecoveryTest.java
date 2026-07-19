package dev.mappywall.core;

import static dev.mappywall.core.BoatDismountRecovery.Action.COMPLETE_REPLAN;
import static dev.mappywall.core.BoatDismountRecovery.Action.FAILED;
import static dev.mappywall.core.BoatDismountRecovery.Action.HOLD;
import static dev.mappywall.core.BoatDismountRecovery.Action.NONE;
import static dev.mappywall.core.BoatDismountRecovery.Action.PULSE_JUMP;
import static dev.mappywall.core.BoatDismountRecovery.Action.REQUEST_DISMOUNT;
import static dev.mappywall.core.BoatDismountRecovery.Action.STEER_EGRESS;
import static dev.mappywall.core.BoatDismountRecovery.Phase.CLEARING_BOAT;
import static dev.mappywall.core.BoatDismountRecovery.Phase.IDLE;
import static dev.mappywall.core.BoatDismountRecovery.Phase.REQUESTING;
import static dev.mappywall.core.BoatDismountRecovery.Phase.SETTLING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mappywall.core.BoatDismountRecovery.Observation;
import dev.mappywall.core.BoatDismountRecovery.RequestPosition;
import org.junit.jupiter.api.Test;

class BoatDismountRecoveryTest {
    private static final int BOAT_ID = 41;

    @Test
    void constructorAndResetExposeAnIdleClearedState() {
        BoatDismountRecovery recovery = new BoatDismountRecovery();

        assertFalse(recovery.active());
        assertEquals(IDLE, recovery.phase());
        assertEquals(-1, recovery.originalBoatEntityId());

        recovery.begin(BOAT_ID, 1.0, 2.0, 3.0);
        recovery.reset();

        assertFalse(recovery.active());
        assertEquals(IDLE, recovery.phase());
        assertEquals(-1, recovery.originalBoatEntityId());
        assertFalse(recovery.suppressBoarding(BOAT_ID));
        assertEquals(NONE, recovery.tick(detachedClearUnstable()));
    }

    @Test
    void beginAndSuppressionRejectNegativeEntityIds() {
        BoatDismountRecovery recovery = new BoatDismountRecovery();

        assertThrows(IllegalArgumentException.class, () -> recovery.begin(-1, 0.0, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> recovery.suppressBoarding(-1));
    }

    @Test
    void tickRejectsNullObservation() {
        BoatDismountRecovery recovery = new BoatDismountRecovery();

        assertThrows(NullPointerException.class, () -> recovery.tick(null));
    }

    @Test
    void requestPositionIsReadableAndFollowsRecoveryLifecycle() {
        BoatDismountRecovery recovery = new BoatDismountRecovery();
        RequestPosition first = new RequestPosition(1.25, 64.0, -2.75);
        RequestPosition second = new RequestPosition(10.0, 70.5, 30.0);

        assertTrue(recovery.requestPosition().isEmpty());

        recovery.begin(BOAT_ID, first.x(), first.y(), first.z());
        assertEquals(first, recovery.requestPosition().orElseThrow());

        recovery.begin(BOAT_ID, 100.0, 200.0, 300.0);
        assertEquals(first, recovery.requestPosition().orElseThrow());

        assertEquals(FAILED, recovery.tick(ridingDifferentVehicle()));
        assertEquals(first, recovery.requestPosition().orElseThrow());

        recovery.begin(BOAT_ID + 1, second.x(), second.y(), second.z());
        assertEquals(second, recovery.requestPosition().orElseThrow());
        recovery.cancel();
        assertEquals(second, recovery.requestPosition().orElseThrow());

        recovery.reset();
        assertTrue(recovery.requestPosition().isEmpty());
    }

    @Test
    void requestPositionRejectsNonFiniteCoordinatesWithoutMutation() {
        BoatDismountRecovery recovery = new BoatDismountRecovery();

        assertThrows(
                IllegalArgumentException.class,
                () -> recovery.begin(BOAT_ID, Double.NaN, 0.0, 0.0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> recovery.begin(BOAT_ID, 0.0, Double.POSITIVE_INFINITY, 0.0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> recovery.begin(BOAT_ID, 0.0, 0.0, Double.NEGATIVE_INFINITY)
        );
        assertTrue(recovery.requestPosition().isEmpty());

        RequestPosition valid = new RequestPosition(1.0, 2.0, 3.0);
        recovery.begin(BOAT_ID, valid.x(), valid.y(), valid.z());
        assertThrows(
                IllegalArgumentException.class,
                () -> recovery.begin(BOAT_ID, Double.NaN, 2.0, 3.0)
        );
        assertEquals(valid, recovery.requestPosition().orElseThrow());

        assertThrows(
                IllegalArgumentException.class,
                () -> new RequestPosition(Double.NaN, 0.0, 0.0)
        );
    }

    @Test
    void repeatedBeginIsIdempotentOnlyForTheSameActiveBoat() {
        BoatDismountRecovery recovery = begunRecovery();
        assertEquals(REQUEST_DISMOUNT, recovery.tick(ridingOriginalBoat()));

        recovery.begin(BOAT_ID, 100.0, 200.0, 300.0);

        assertEquals(HOLD, recovery.tick(ridingOriginalBoat()));
        assertThrows(
                IllegalStateException.class,
                () -> recovery.begin(BOAT_ID + 1, 0.0, 0.0, 0.0)
        );
        assertTrue(recovery.active());
        assertEquals(BOAT_ID, recovery.originalBoatEntityId());
    }

    @Test
    void requestRetriesAtMostEveryTenTicksWhileStillPassenger() {
        BoatDismountRecovery recovery = begunRecovery();

        assertEquals(REQUEST_DISMOUNT, recovery.tick(ridingOriginalBoat()));
        for (int tick = 2; tick <= 10; tick++) {
            assertEquals(HOLD, recovery.tick(ridingOriginalBoat()), "active tick " + tick);
        }
        assertEquals(REQUEST_DISMOUNT, recovery.tick(ridingOriginalBoat()), "active tick 11");
        for (int tick = 12; tick <= 20; tick++) {
            assertEquals(HOLD, recovery.tick(ridingOriginalBoat()), "active tick " + tick);
        }
        assertEquals(REQUEST_DISMOUNT, recovery.tick(ridingOriginalBoat()), "active tick 21");
    }

    @Test
    void differentVehicleDuringRecoveryFailsSafely() {
        BoatDismountRecovery recovery = begunRecovery();

        assertEquals(FAILED, recovery.tick(ridingDifferentVehicle()));
        assertFalse(recovery.active());
        assertEquals(IDLE, recovery.phase());
        assertEquals(BOAT_ID, recovery.originalBoatEntityId());
        assertTrue(recovery.suppressBoarding(BOAT_ID));
        assertEquals(NONE, recovery.tick(ridingDifferentVehicle()));
    }

    @Test
    void detachRequiresServerMovementOrThreeStableDetachedTicks() {
        BoatDismountRecovery confirmedByTicks = begunRecovery();
        Observation detached = detachedTouching(false, true, true);

        assertEquals(HOLD, confirmedByTicks.tick(detached));
        assertEquals(REQUESTING, confirmedByTicks.phase());
        assertEquals(HOLD, confirmedByTicks.tick(detached));
        assertEquals(REQUESTING, confirmedByTicks.phase());
        assertEquals(STEER_EGRESS, confirmedByTicks.tick(detached));
        assertEquals(CLEARING_BOAT, confirmedByTicks.phase());

        BoatDismountRecovery confirmedByMovement = begunRecovery();
        assertEquals(STEER_EGRESS, confirmedByMovement.tick(detachedTouching(true, true, true)));
        assertEquals(CLEARING_BOAT, confirmedByMovement.phase());
    }

    @Test
    void detachedBoatContactSteersWithoutCompleting() {
        BoatDismountRecovery recovery = begunRecovery();
        Observation progressingEgress = detachedTouching(true, true, true);

        for (int tick = 0; tick < 5; tick++) {
            assertEquals(STEER_EGRESS, recovery.tick(progressingEgress));
        }

        assertTrue(recovery.active());
        assertEquals(CLEARING_BOAT, recovery.phase());
    }

    @Test
    void stalledEgressEmitsOnlyOneJumpPulse() {
        BoatDismountRecovery recovery = begunRecovery();
        Observation stalled = detachedTouching(true, true, false);
        Observation progressing = detachedTouching(false, true, true);

        for (int tick = 1; tick <= 5; tick++) {
            assertEquals(STEER_EGRESS, recovery.tick(stalled), "initial stall tick " + tick);
        }
        assertEquals(STEER_EGRESS, recovery.tick(progressing));
        for (int tick = 1; tick <= 5; tick++) {
            assertEquals(STEER_EGRESS, recovery.tick(stalled), "reset stall tick " + tick);
        }
        assertEquals(PULSE_JUMP, recovery.tick(stalled), "sixth consecutive stalled tick");
        for (int tick = 1; tick <= 8; tick++) {
            assertEquals(STEER_EGRESS, recovery.tick(stalled), "post-pulse stall tick " + tick);
        }

        assertTrue(recovery.active());
    }

    @Test
    void threeStableBlockTicksCompleteExactlyOnce() {
        BoatDismountRecovery recovery = begunRecovery();

        assertEquals(HOLD, recovery.tick(detachedClearBlock(true)));
        assertEquals(SETTLING, recovery.phase());
        assertEquals(HOLD, recovery.tick(detachedClearBlock(false)));
        assertEquals(COMPLETE_REPLAN, recovery.tick(detachedClearBlock(false)));
        assertFalse(recovery.active());
        assertEquals(IDLE, recovery.phase());
        assertTrue(recovery.suppressBoarding(BOAT_ID));
        assertEquals(NONE, recovery.tick(detachedClearBlock(false)));
    }

    @Test
    void threeStableWaterTicksCompleteExactlyOnce() {
        BoatDismountRecovery recovery = begunRecovery();

        assertEquals(HOLD, recovery.tick(detachedClearWater(true)));
        assertEquals(SETTLING, recovery.phase());
        assertEquals(HOLD, recovery.tick(detachedClearWater(false)));
        assertEquals(COMPLETE_REPLAN, recovery.tick(detachedClearWater(false)));
        assertFalse(recovery.active());
        assertEquals(NONE, recovery.tick(detachedClearWater(false)));
    }

    @Test
    void contactDuringSettlingResetsStability() {
        BoatDismountRecovery recovery = begunRecovery();

        assertEquals(HOLD, recovery.tick(detachedClearBlock(true)));
        assertEquals(HOLD, recovery.tick(detachedClearBlock(false)));
        assertEquals(HOLD, recovery.tick(detachedTouching(false, false, false)));
        assertEquals(CLEARING_BOAT, recovery.phase());

        assertEquals(HOLD, recovery.tick(detachedClearBlock(false)));
        assertEquals(HOLD, recovery.tick(detachedClearBlock(false)));
        assertEquals(COMPLETE_REPLAN, recovery.tick(detachedClearBlock(false)));
    }

    @Test
    void touchingSignalResetsSettlingEvenWhenBoatPresenceIsStale() {
        BoatDismountRecovery recovery = begunRecovery();

        assertEquals(HOLD, recovery.tick(detachedClearBlock(true)));
        assertEquals(HOLD, recovery.tick(detachedClearBlock(false)));

        Observation stalePresenceWhileTouching = new Observation(
                false,
                false,
                false,
                true,
                true,
                false,
                false,
                false,
                false
        );
        assertEquals(HOLD, recovery.tick(stalePresenceWhileTouching));
        assertEquals(CLEARING_BOAT, recovery.phase());

        assertEquals(HOLD, recovery.tick(detachedClearBlock(false)));
        assertEquals(HOLD, recovery.tick(detachedClearBlock(false)));
        assertEquals(COMPLETE_REPLAN, recovery.tick(detachedClearBlock(false)));
    }

    @Test
    void lossOfSupportDuringSettlingResetsStability() {
        BoatDismountRecovery recovery = begunRecovery();

        assertEquals(HOLD, recovery.tick(detachedClearBlock(true)));
        assertEquals(HOLD, recovery.tick(detachedClearBlock(false)));
        assertEquals(HOLD, recovery.tick(detachedClearUnstable()));
        assertEquals(CLEARING_BOAT, recovery.phase());

        assertEquals(HOLD, recovery.tick(detachedClearBlock(false)));
        assertEquals(HOLD, recovery.tick(detachedClearBlock(false)));
        assertEquals(COMPLETE_REPLAN, recovery.tick(detachedClearBlock(false)));
    }

    @Test
    void formerBoatIsSuppressedForSixtyTicksButOtherBoatsAreAllowed() {
        BoatDismountRecovery recovery = begunRecovery();
        recovery.cancel();

        assertTrue(recovery.suppressBoarding(BOAT_ID));
        assertFalse(recovery.suppressBoarding(BOAT_ID + 1));
        for (int tick = 1; tick < 60; tick++) {
            assertEquals(NONE, recovery.tick(detachedClearUnstable()));
            assertTrue(recovery.suppressBoarding(BOAT_ID), "normal tick " + tick);
            assertFalse(recovery.suppressBoarding(BOAT_ID + 1));
        }

        assertEquals(NONE, recovery.tick(detachedClearUnstable()));
        assertFalse(recovery.suppressBoarding(BOAT_ID));
    }

    @Test
    void terminalAndCancelOutcomesRetainTheExactSuppressionRemainder() {
        BoatDismountRecovery completed = begunRecovery();
        assertEquals(HOLD, completed.tick(detachedClearBlock(true)));
        assertEquals(HOLD, completed.tick(detachedClearBlock(false)));
        assertEquals(COMPLETE_REPLAN, completed.tick(detachedClearBlock(false)));
        assertEquals(57, ticksUntilBoardingAllowed(completed));

        BoatDismountRecovery failed = begunRecovery();
        assertEquals(FAILED, failed.tick(ridingDifferentVehicle()));
        assertEquals(59, ticksUntilBoardingAllowed(failed));

        BoatDismountRecovery cancelled = begunRecovery();
        for (int tick = 0; tick < 5; tick++) {
            cancelled.tick(ridingOriginalBoat());
        }
        cancelled.cancel();
        assertEquals(55, ticksUntilBoardingAllowed(cancelled));
    }

    @Test
    void timeoutFailsInsteadOfRestarting() {
        BoatDismountRecovery recovery = begunRecovery();
        Observation blocked = detachedTouching(true, false, false);

        for (int tick = 1; tick < 100; tick++) {
            assertEquals(HOLD, recovery.tick(blocked), "active tick " + tick);
        }
        assertEquals(FAILED, recovery.tick(blocked), "active tick 100");
        assertFalse(recovery.active());
        assertEquals(IDLE, recovery.phase());
        assertEquals(NONE, recovery.tick(blocked));
    }

    @Test
    void timeoutWinsOverBothARequestAndAnOtherwiseCompletingTick() {
        BoatDismountRecovery requesting = begunRecovery();
        for (int tick = 1; tick < 100; tick++) {
            requesting.tick(ridingOriginalBoat());
        }
        assertEquals(FAILED, requesting.tick(ridingOriginalBoat()));

        BoatDismountRecovery settling = begunRecovery();
        for (int tick = 1; tick <= 97; tick++) {
            settling.tick(detachedClearUnstable());
        }
        assertEquals(HOLD, settling.tick(detachedClearBlock(false)));
        assertEquals(HOLD, settling.tick(detachedClearBlock(false)));
        assertEquals(FAILED, settling.tick(detachedClearBlock(false)));
    }

    @Test
    void softCancelRetainsSuppressionWhileHardResetClearsIt() {
        BoatDismountRecovery recovery = begunRecovery();
        assertEquals(REQUEST_DISMOUNT, recovery.tick(ridingOriginalBoat()));

        recovery.cancel();

        assertFalse(recovery.active());
        assertEquals(IDLE, recovery.phase());
        assertEquals(BOAT_ID, recovery.originalBoatEntityId());
        assertTrue(recovery.suppressBoarding(BOAT_ID));
        recovery.cancel();
        assertTrue(recovery.suppressBoarding(BOAT_ID));

        recovery.begin(BOAT_ID + 1, 10.0, 20.0, 30.0);
        assertTrue(recovery.active());
        assertEquals(BOAT_ID + 1, recovery.originalBoatEntityId());
        assertFalse(recovery.suppressBoarding(BOAT_ID));
        assertTrue(recovery.suppressBoarding(BOAT_ID + 1));

        recovery.reset();

        assertFalse(recovery.active());
        assertEquals(IDLE, recovery.phase());
        assertEquals(-1, recovery.originalBoatEntityId());
        assertFalse(recovery.suppressBoarding(BOAT_ID + 1));
    }

    private static BoatDismountRecovery begunRecovery() {
        BoatDismountRecovery recovery = new BoatDismountRecovery();
        recovery.begin(BOAT_ID, 1.25, 64.0, -2.75);
        return recovery;
    }

    private static int ticksUntilBoardingAllowed(BoatDismountRecovery recovery) {
        int ticks = 0;
        while (recovery.suppressBoarding(BOAT_ID)) {
            recovery.tick(detachedClearUnstable());
            ticks++;
            assertTrue(ticks <= 60);
        }
        return ticks;
    }

    private static Observation ridingOriginalBoat() {
        return new Observation(true, true, true, true, false, false, false, false, false);
    }

    private static Observation ridingDifferentVehicle() {
        return new Observation(true, false, true, false, false, false, false, false, false);
    }

    private static Observation detachedTouching(
            boolean serverPositionChanged,
            boolean egressAvailable,
            boolean egressProgress
    ) {
        return new Observation(
                false,
                false,
                true,
                true,
                false,
                false,
                serverPositionChanged,
                egressAvailable,
                egressProgress
        );
    }

    private static Observation detachedClearBlock(boolean serverPositionChanged) {
        return new Observation(
                false,
                false,
                true,
                false,
                true,
                false,
                serverPositionChanged,
                false,
                false
        );
    }

    private static Observation detachedClearWater(boolean serverPositionChanged) {
        return new Observation(
                false,
                false,
                false,
                false,
                false,
                true,
                serverPositionChanged,
                false,
                false
        );
    }

    private static Observation detachedClearUnstable() {
        return new Observation(false, false, true, false, false, false, false, false, false);
    }
}
