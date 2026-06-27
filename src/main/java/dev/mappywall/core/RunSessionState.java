package dev.mappywall.core;

import java.util.List;
import java.util.Objects;

public record RunSessionState(
        int currentStep,
        boolean paused,
        PlayerBlockPos lastPlayerPos,
        String movementProfile,
        List<String> warnings
) {
    public RunSessionState {
        if (currentStep < 0) {
            throw new IllegalArgumentException("currentStep must be non-negative");
        }
        Objects.requireNonNull(movementProfile, "movementProfile");
        Objects.requireNonNull(warnings, "warnings");
        warnings = List.copyOf(warnings);
    }

    public RunSessionState withCurrentStep(int newCurrentStep) {
        return new RunSessionState(newCurrentStep, paused, lastPlayerPos, movementProfile, warnings);
    }

    public RunSessionState withPaused(boolean newPaused) {
        return new RunSessionState(currentStep, newPaused, lastPlayerPos, movementProfile, warnings);
    }

    public RunSessionState withLastPlayerPos(PlayerBlockPos newLastPlayerPos) {
        return new RunSessionState(currentStep, paused, newLastPlayerPos, movementProfile, warnings);
    }

    public RunSessionState withWarnings(List<String> newWarnings) {
        return new RunSessionState(currentStep, paused, lastPlayerPos, movementProfile, newWarnings);
    }
}

