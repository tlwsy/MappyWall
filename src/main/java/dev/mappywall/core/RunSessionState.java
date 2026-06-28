package dev.mappywall.core;

import java.util.List;
import java.util.Objects;

public record RunSessionState(
        int currentStep,
        boolean paused,
        PlayerBlockPos lastPlayerPos,
        String movementProfile,
        int fillWaypointIndex,
        List<String> warnings
) {
    public RunSessionState {
        if (currentStep < 0) {
            throw new IllegalArgumentException("currentStep must be non-negative");
        }
        if (fillWaypointIndex < 0) {
            fillWaypointIndex = 0;
        }
        Objects.requireNonNull(movementProfile, "movementProfile");
        Objects.requireNonNull(warnings, "warnings");
        warnings = List.copyOf(warnings);
    }

    public RunSessionState(
            int currentStep,
            boolean paused,
            PlayerBlockPos lastPlayerPos,
            String movementProfile,
            List<String> warnings
    ) {
        this(currentStep, paused, lastPlayerPos, movementProfile, 0, warnings);
    }

    public RunSessionState withCurrentStep(int newCurrentStep) {
        return new RunSessionState(newCurrentStep, paused, lastPlayerPos, movementProfile, fillWaypointIndex, warnings);
    }

    public RunSessionState withPaused(boolean newPaused) {
        return new RunSessionState(currentStep, newPaused, lastPlayerPos, movementProfile, fillWaypointIndex, warnings);
    }

    public RunSessionState withLastPlayerPos(PlayerBlockPos newLastPlayerPos) {
        return new RunSessionState(currentStep, paused, newLastPlayerPos, movementProfile, fillWaypointIndex, warnings);
    }

    public RunSessionState withWarnings(List<String> newWarnings) {
        return new RunSessionState(currentStep, paused, lastPlayerPos, movementProfile, fillWaypointIndex, newWarnings);
    }

    public RunSessionState withFillWaypointIndex(int newFillWaypointIndex) {
        return new RunSessionState(currentStep, paused, lastPlayerPos, movementProfile, newFillWaypointIndex, warnings);
    }
}
