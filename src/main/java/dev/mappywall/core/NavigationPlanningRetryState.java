package dev.mappywall.core;

import java.util.Objects;

/**
 * Owns planning-submission backoff independently from active path execution.
 */
public final class NavigationPlanningRetryState {
    private final NavigationPlanningCadence cadence;
    private int remainingTicks;

    public NavigationPlanningRetryState(NavigationPlanningCadence cadence) {
        this.cadence = Objects.requireNonNull(cadence, "cadence");
    }

    public void beginTick() {
        if (remainingTicks > 0) {
            remainingTicks--;
        }
    }

    public void onSuccess() {
        remainingTicks = 0;
    }

    public void onNoPath() {
        remainingTicks = cadence.failedRetryTicks();
    }

    public void onNodeLimit() {
        remainingTicks = cadence.nodeLimitYieldTicks();
    }

    public void onException() {
        remainingTicks = cadence.failedRetryTicks();
    }

    public void onCompletionException(boolean currentRequest) {
        if (currentRequest) {
            onException();
        } else {
            forceFreshSnapshot();
        }
    }

    public void onInvalidPlan() {
        remainingTicks = cadence.failedRetryTicks();
    }

    public void forceFreshSnapshot() {
        remainingTicks = 0;
    }

    public boolean canSubmit() {
        return remainingTicks == 0;
    }

    public int remainingTicks() {
        return remainingTicks;
    }
}
