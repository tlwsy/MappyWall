package dev.mappywall.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NavigationPlanningRetryStateTest {
    private final NavigationPlanningRetryState retry =
            new NavigationPlanningRetryState(NavigationPlanningCadence.defaults());

    @Test
    void successfulSegmentExhaustionHasNoFailureBackoff() {
        retry.onNoPath();

        retry.onSuccess();

        assertEquals(0, retry.remainingTicks());
        assertTrue(retry.canSubmit());
    }

    @Test
    void noPathUsesTwentyTickFailureBackoff() {
        retry.beginTick();

        retry.onNoPath();

        assertEquals(20, retry.remainingTicks());
        assertFalse(retry.canSubmit());

        retry.beginTick();
        assertEquals(19, retry.remainingTicks());
    }

    @Test
    void nodeLimitUsesTwoTickYieldWithoutHardFailure() {
        retry.onNodeLimit();

        assertEquals(2, retry.remainingTicks());
        assertFalse(retry.canSubmit());

        retry.beginTick();
        assertEquals(1, retry.remainingTicks());
        retry.beginTick();
        assertEquals(0, retry.remainingTicks());
        assertTrue(retry.canSubmit());
    }

    @Test
    void currentCompletionExceptionUsesFailureBackoff() {
        retry.onCompletionException(true);

        assertEquals(20, retry.remainingTicks());
        assertFalse(retry.canSubmit());
    }

    @Test
    void staleCompletionExceptionForcesFreshSnapshotWithoutBackoff() {
        retry.onNoPath();

        retry.onCompletionException(false);

        assertEquals(0, retry.remainingTicks());
        assertTrue(retry.canSubmit());
    }

    @Test
    void invalidPlanUsesFailureBackoff() {
        retry.onInvalidPlan();

        assertEquals(20, retry.remainingTicks());
        assertFalse(retry.canSubmit());
    }

    @Test
    void staleResultForcesFreshSnapshotWithoutBackoff() {
        retry.onNoPath();

        retry.forceFreshSnapshot();

        assertEquals(0, retry.remainingTicks());
        assertTrue(retry.canSubmit());
    }

    @Test
    void beginTickNeverUnderflows() {
        retry.beginTick();
        retry.beginTick();

        assertEquals(0, retry.remainingTicks());
        assertTrue(retry.canSubmit());
    }
}
