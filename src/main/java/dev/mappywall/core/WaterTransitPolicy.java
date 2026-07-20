package dev.mappywall.core;

import java.util.Objects;
import java.util.OptionalInt;

public final class WaterTransitPolicy {
    public static final int DEFAULT_MINIMUM_BOAT_DISTANCE_BLOCKS = 12;
    public static final int MINIMUM_BOAT_DISTANCE_BLOCKS = 1;
    public static final int MAXIMUM_BOAT_DISTANCE_BLOCKS = 128;

    public enum TravelDecision {
        SWIM,
        ACQUIRE_BOAT,
        CONTINUE_RIDING
    }

    public record RunObservation(
            OptionalInt surfaceY,
            double futureConfirmedDistanceBlocks,
            int minimumBoatDistanceBlocks,
            boolean ridingBoat
    ) {
        public RunObservation {
            Objects.requireNonNull(surfaceY, "surfaceY");
            if (!Double.isFinite(futureConfirmedDistanceBlocks)
                    || futureConfirmedDistanceBlocks < 0.0) {
                throw new IllegalArgumentException("future distance must be finite and non-negative");
            }
            if (minimumBoatDistanceBlocks < MINIMUM_BOAT_DISTANCE_BLOCKS
                    || minimumBoatDistanceBlocks > MAXIMUM_BOAT_DISTANCE_BLOCKS) {
                throw new IllegalArgumentException("minimum boat distance is out of range");
            }
        }
    }

    private OptionalInt surfaceY = OptionalInt.empty();
    private double completedDistanceBlocks;
    private boolean acquisitionLatched;

    public TravelDecision observe(RunObservation observation) {
        Objects.requireNonNull(observation, "observation");
        if (observation.surfaceY().isEmpty()) {
            leaveWaterRun();
            return TravelDecision.SWIM;
        }
        int observedY = observation.surfaceY().getAsInt();
        if (surfaceY.isEmpty() || surfaceY.getAsInt() != observedY) {
            beginRun(observedY);
        }
        if (completedDistanceBlocks + observation.futureConfirmedDistanceBlocks()
                >= observation.minimumBoatDistanceBlocks()) {
            acquisitionLatched = true;
        }
        if (observation.ridingBoat()) {
            return TravelDecision.CONTINUE_RIDING;
        }
        return acquisitionLatched ? TravelDecision.ACQUIRE_BOAT : TravelDecision.SWIM;
    }

    public void recordCompletedEdge(int edgeSurfaceY, double distanceBlocks) {
        if (!Double.isFinite(distanceBlocks) || distanceBlocks < 0.0) {
            throw new IllegalArgumentException("completed distance must be finite and non-negative");
        }
        if (surfaceY.isEmpty() || surfaceY.getAsInt() != edgeSurfaceY) {
            beginRun(edgeSurfaceY);
            return;
        }
        completedDistanceBlocks += distanceBlocks;
    }

    public void leaveWaterRun() {
        surfaceY = OptionalInt.empty();
        completedDistanceBlocks = 0.0;
        acquisitionLatched = false;
    }

    public void reset() {
        leaveWaterRun();
    }

    public OptionalInt surfaceY() {
        return surfaceY;
    }

    public double completedDistanceBlocks() {
        return completedDistanceBlocks;
    }

    public boolean acquisitionLatched() {
        return acquisitionLatched;
    }

    private void beginRun(int observedY) {
        surfaceY = OptionalInt.of(observedY);
        completedDistanceBlocks = 0.0;
        acquisitionLatched = false;
    }
}
