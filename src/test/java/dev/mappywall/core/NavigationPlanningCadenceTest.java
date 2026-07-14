package dev.mappywall.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class NavigationPlanningCadenceTest {
    @Test
    void defaultsMatchTheRollingPlanningPipeline() {
        NavigationPlanningCadence cadence = NavigationPlanningCadence.defaults();

        assertAll(
                () -> assertEquals(28, cadence.lookaheadRemainingSteps()),
                () -> assertEquals(512, cadence.snapshotColumnsPerTick()),
                () -> assertEquals(20, cadence.failedRetryTicks()),
                () -> assertEquals(2, cadence.nodeLimitYieldTicks())
        );
    }

    @Test
    void rejectsEveryNonPositiveCadenceValue() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new NavigationPlanningCadence(0, 512, 20, 2)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new NavigationPlanningCadence(28, 0, 20, 2)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new NavigationPlanningCadence(28, 512, 0, 2)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new NavigationPlanningCadence(28, 512, 20, 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new NavigationPlanningCadence(-1, 512, 20, 2)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new NavigationPlanningCadence(28, -1, 20, 2)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new NavigationPlanningCadence(28, 512, -1, 2)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new NavigationPlanningCadence(28, 512, 20, -1))
        );
    }
}
