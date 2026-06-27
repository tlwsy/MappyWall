package dev.mappywall.core;

import java.util.Objects;

public record RouteStep(
        WallPos wallPos,
        MapRegion region,
        BlockTarget targetBlock,
        RouteStepState state
) {
    public RouteStep {
        Objects.requireNonNull(wallPos, "wallPos");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(targetBlock, "targetBlock");
        Objects.requireNonNull(state, "state");
    }

    public RouteStep withState(RouteStepState newState) {
        return new RouteStep(wallPos, region, targetBlock, newState);
    }
}

