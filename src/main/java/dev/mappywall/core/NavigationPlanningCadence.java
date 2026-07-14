package dev.mappywall.core;

/**
 * Immutable timing policy for the rolling local-navigation planning pipeline.
 */
public record NavigationPlanningCadence(
        int lookaheadRemainingSteps,
        int snapshotColumnsPerTick,
        int failedRetryTicks,
        int nodeLimitYieldTicks
) {
    public NavigationPlanningCadence {
        requirePositive(lookaheadRemainingSteps, "lookaheadRemainingSteps");
        requirePositive(snapshotColumnsPerTick, "snapshotColumnsPerTick");
        requirePositive(failedRetryTicks, "failedRetryTicks");
        requirePositive(nodeLimitYieldTicks, "nodeLimitYieldTicks");
    }

    public static NavigationPlanningCadence defaults() {
        return new NavigationPlanningCadence(28, 512, 20, 2);
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
