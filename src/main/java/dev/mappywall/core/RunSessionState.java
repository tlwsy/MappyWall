package dev.mappywall.core;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record RunSessionState(
        int currentStep,
        boolean paused,
        PlayerBlockPos lastPlayerPos,
        String movementProfile,
        int fillWaypointIndex,
        List<String> warnings,
        PendingMapZoom pendingMapZoom,
        PendingMapOpening pendingMapOpening,
        Map<Integer, Integer> knownMapScales,
        int bindingDataVersion
) {
    public static final int CURRENT_BINDING_DATA_VERSION = 1;

    public RunSessionState {
        if (currentStep < 0) {
            throw new IllegalArgumentException("currentStep must be non-negative");
        }
        if (fillWaypointIndex < 0) {
            fillWaypointIndex = 0;
        }
        if (bindingDataVersion < 0 || bindingDataVersion > CURRENT_BINDING_DATA_VERSION) {
            throw new IllegalArgumentException("unsupported binding data version: " + bindingDataVersion);
        }
        Objects.requireNonNull(movementProfile, "movementProfile");
        Objects.requireNonNull(warnings, "warnings");
        warnings = List.copyOf(warnings);
        if (knownMapScales == null) {
            knownMapScales = Map.of();
        } else {
            for (Map.Entry<Integer, Integer> entry : knownMapScales.entrySet()) {
                if (entry.getKey() == null || entry.getKey() < 0 || entry.getValue() == null) {
                    throw new IllegalArgumentException("known map scale entry is invalid");
                }
                MapRegionMath.validateScale(entry.getValue());
            }
            knownMapScales = Map.copyOf(knownMapScales);
        }
    }

    public RunSessionState(
            int currentStep,
            boolean paused,
            PlayerBlockPos lastPlayerPos,
            String movementProfile,
            int fillWaypointIndex,
            List<String> warnings
    ) {
        this(currentStep, paused, lastPlayerPos, movementProfile, fillWaypointIndex, warnings,
                null, null, Map.of(), CURRENT_BINDING_DATA_VERSION);
    }

    public RunSessionState(
            int currentStep,
            boolean paused,
            PlayerBlockPos lastPlayerPos,
            String movementProfile,
            int fillWaypointIndex,
            List<String> warnings,
            PendingMapZoom pendingMapZoom
    ) {
        this(currentStep, paused, lastPlayerPos, movementProfile, fillWaypointIndex, warnings,
                pendingMapZoom, null, Map.of(), CURRENT_BINDING_DATA_VERSION);
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
        return new RunSessionState(
                newCurrentStep, paused, lastPlayerPos, movementProfile, fillWaypointIndex, warnings,
                pendingMapZoom, pendingMapOpening, knownMapScales, bindingDataVersion
        );
    }

    public RunSessionState withPaused(boolean newPaused) {
        return new RunSessionState(
                currentStep, newPaused, lastPlayerPos, movementProfile, fillWaypointIndex, warnings,
                pendingMapZoom, pendingMapOpening, knownMapScales, bindingDataVersion
        );
    }

    public RunSessionState withLastPlayerPos(PlayerBlockPos newLastPlayerPos) {
        return new RunSessionState(
                currentStep, paused, newLastPlayerPos, movementProfile, fillWaypointIndex, warnings,
                pendingMapZoom, pendingMapOpening, knownMapScales, bindingDataVersion
        );
    }

    public RunSessionState withWarnings(List<String> newWarnings) {
        return new RunSessionState(
                currentStep, paused, lastPlayerPos, movementProfile, fillWaypointIndex, newWarnings,
                pendingMapZoom, pendingMapOpening, knownMapScales, bindingDataVersion
        );
    }

    public RunSessionState withFillWaypointIndex(int newFillWaypointIndex) {
        return new RunSessionState(
                currentStep, paused, lastPlayerPos, movementProfile, newFillWaypointIndex, warnings,
                pendingMapZoom, pendingMapOpening, knownMapScales, bindingDataVersion
        );
    }

    public RunSessionState withPendingMapZoom(PendingMapZoom newPendingMapZoom) {
        return new RunSessionState(
                currentStep, paused, lastPlayerPos, movementProfile, fillWaypointIndex, warnings,
                newPendingMapZoom, pendingMapOpening, knownMapScales, bindingDataVersion
        );
    }

    public RunSessionState withPendingMapOpening(PendingMapOpening newPendingMapOpening) {
        return new RunSessionState(
                currentStep, paused, lastPlayerPos, movementProfile, fillWaypointIndex, warnings,
                pendingMapZoom, newPendingMapOpening, knownMapScales, bindingDataVersion
        );
    }

    public RunSessionState withBindingDataVersion(int newBindingDataVersion) {
        return new RunSessionState(
                currentStep, paused, lastPlayerPos, movementProfile, fillWaypointIndex, warnings,
                pendingMapZoom, pendingMapOpening, knownMapScales, newBindingDataVersion
        );
    }

    public Integer knownScaleForMapId(int mapId) {
        return knownMapScales.get(mapId);
    }

    public RunSessionState withKnownMapScale(int mapId, int scale) {
        if (mapId < 0) {
            throw new IllegalArgumentException("mapId must be non-negative");
        }
        MapRegionMath.validateScale(scale);
        Map<Integer, Integer> updated = new HashMap<>(knownMapScales);
        updated.put(mapId, scale);
        return new RunSessionState(
                currentStep, paused, lastPlayerPos, movementProfile, fillWaypointIndex, warnings,
                pendingMapZoom, pendingMapOpening, updated, bindingDataVersion
        );
    }

    /** Clears stale persisted transactions only when the user explicitly asks to resume/retry. */
    public RunSessionState clearTimedOutTransactions(Instant now, Duration timeout) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(timeout, "timeout");
        PendingMapZoom retainedZoom = pendingMapZoom != null && pendingMapZoom.timedOutAt(now, timeout)
                ? null
                : pendingMapZoom;
        PendingMapOpening retainedOpening = pendingMapOpening != null && pendingMapOpening.timedOutAt(now, timeout)
                ? null
                : pendingMapOpening;
        if (retainedZoom == pendingMapZoom && retainedOpening == pendingMapOpening) {
            return this;
        }
        return new RunSessionState(
                currentStep, paused, lastPlayerPos, movementProfile, fillWaypointIndex, warnings,
                retainedZoom, retainedOpening, knownMapScales, bindingDataVersion
        );
    }
}
