package dev.mappywall.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.AABB;

final class WaterRouteEvidenceAdapter {
    private static final double BOAT_HALF_WIDTH = 0.6875;
    private static final double BOAT_HEIGHT = 0.5625;

    record ResolvedSurface(BlockPos routePos, BlockPos waterPos) {
    }

    record Evidence(
            OptionalInt surfaceY,
            double futureConfirmedDistanceBlocks,
            List<ResolvedSurface> surfaces
    ) {
        Evidence {
            Objects.requireNonNull(surfaceY, "surfaceY");
            surfaces = List.copyOf(surfaces);
        }

        static Evidence none() {
            return new Evidence(OptionalInt.empty(), 0.0, List.of());
        }
    }

    interface SurfaceProbe {
        boolean loaded(BlockPos pos);

        boolean water(BlockPos pos);

        boolean boatEnvelopeClear(BlockPos waterSurface);
    }

    @FunctionalInterface
    interface SurfaceLookup {
        Optional<ResolvedSurface> resolve(LocalPathPlanner.PathStep step);
    }

    static Optional<ResolvedSurface> resolveBoatableSurface(
            BlockPos waypoint,
            int maxY,
            SurfaceProbe probe
    ) {
        Objects.requireNonNull(waypoint, "waypoint");
        Objects.requireNonNull(probe, "probe");
        if (!probe.loaded(waypoint) || !probe.water(waypoint)) {
            return Optional.empty();
        }
        BlockPos surface = waypoint;
        while (surface.getY() < maxY) {
            BlockPos above = surface.above();
            if (!probe.loaded(above)) {
                return Optional.empty();
            }
            if (!probe.water(above)) {
                return probe.boatEnvelopeClear(surface)
                        ? Optional.of(new ResolvedSurface(waypoint, surface))
                        : Optional.empty();
            }
            surface = above;
        }
        return Optional.empty();
    }

    static Evidence collect(
            BlockPos anchor,
            List<LocalPathPlanner.PathStep> acceptedSteps,
            double proofLimit,
            SurfaceLookup lookup
    ) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(acceptedSteps, "acceptedSteps");
        Objects.requireNonNull(lookup, "lookup");
        if (!Double.isFinite(proofLimit) || proofLimit <= 0.0) {
            throw new IllegalArgumentException("proofLimit must be finite and positive");
        }

        ArrayList<ResolvedSurface> surfaces = new ArrayList<>();
        OptionalInt surfaceY = OptionalInt.empty();
        BlockPos previous = anchor;
        double distance = 0.0;
        for (LocalPathPlanner.PathStep step : acceptedSteps) {
            if (step.action() != LocalPathPlanner.StepAction.SWIM) {
                break;
            }
            ResolvedSurface resolved = lookup.resolve(step).orElse(null);
            if (resolved == null) {
                break;
            }
            int resolvedY = resolved.waterPos().getY();
            if (surfaceY.isPresent() && surfaceY.getAsInt() != resolvedY) {
                break;
            }
            int dx = Math.abs(step.pos().getX() - previous.getX());
            int dz = Math.abs(step.pos().getZ() - previous.getZ());
            if (dx > 1 || dz > 1 || dx + dz == 0) {
                break;
            }
            if (surfaceY.isEmpty()) {
                surfaceY = OptionalInt.of(resolvedY);
            }
            distance += Math.hypot(dx, dz);
            surfaces.add(resolved);
            previous = step.pos();
            if (distance >= proofLimit) {
                break;
            }
        }
        return new Evidence(surfaceY, distance, surfaces);
    }

    static AABB boatEnvelope(BlockPos waterSurface) {
        double centerX = waterSurface.getX() + 0.5;
        double centerZ = waterSurface.getZ() + 0.5;
        double bottomY = waterSurface.getY() + 1.0;
        return new AABB(
                centerX - BOAT_HALF_WIDTH,
                bottomY,
                centerZ - BOAT_HALF_WIDTH,
                centerX + BOAT_HALF_WIDTH,
                bottomY + BOAT_HEIGHT,
                centerZ + BOAT_HALF_WIDTH
        );
    }

    Optional<ResolvedSurface> resolveBoatableSurface(Minecraft client, BlockPos waypoint) {
        Objects.requireNonNull(client, "client");
        if (client.level == null) {
            return Optional.empty();
        }
        return resolveBoatableSurface(
                waypoint,
                client.level.getMaxY() - 1,
                new SurfaceProbe() {
                    @Override
                    public boolean loaded(BlockPos pos) {
                        return client.level.hasChunkAt(pos);
                    }

                    @Override
                    public boolean water(BlockPos pos) {
                        return client.level.getFluidState(pos).is(FluidTags.WATER);
                    }

                    @Override
                    public boolean boatEnvelopeClear(BlockPos waterSurface) {
                        AABB envelope = boatEnvelope(waterSurface);
                        List<BlockPos> corners = List.of(
                                BlockPos.containing(envelope.minX, envelope.minY, envelope.minZ),
                                BlockPos.containing(envelope.minX, envelope.minY, envelope.maxZ),
                                BlockPos.containing(envelope.maxX, envelope.minY, envelope.minZ),
                                BlockPos.containing(envelope.maxX, envelope.minY, envelope.maxZ)
                        );
                        return corners.stream().allMatch(client.level::hasChunkAt)
                                && envelope.minY >= client.level.getMinY()
                                && envelope.maxY <= client.level.getMaxY()
                                && client.level.getWorldBorder().isWithinBounds(envelope)
                                && client.level.noBlockCollision(null, envelope);
                    }
                }
        );
    }

    Evidence collect(
            Minecraft client,
            BlockPos anchor,
            List<LocalPathPlanner.PathStep> acceptedSteps,
            double proofLimit
    ) {
        return collect(
                anchor,
                acceptedSteps,
                proofLimit,
                step -> resolveBoatableSurface(client, step.pos())
        );
    }

    OptionalInt resolveBoatSurfaceY(Minecraft client, AbstractBoat boat) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(boat, "boat");
        if (client.level == null) {
            return OptionalInt.empty();
        }
        BlockPos hull = BlockPos.containing(
                boat.getX(), boat.getBoundingBox().minY - 0.05, boat.getZ());
        for (int depth = 0; depth <= 2; depth++) {
            Optional<ResolvedSurface> resolved = resolveBoatableSurface(
                    client, hull.below(depth));
            if (resolved.isPresent()) {
                return OptionalInt.of(resolved.orElseThrow().waterPos().getY());
            }
        }
        return OptionalInt.empty();
    }
}
