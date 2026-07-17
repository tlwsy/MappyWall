package dev.mappywall.client;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

final class BoatEgressSelector {
    interface EgressProbe {
        boolean loaded(BlockPos feet);

        boolean bodyClear(BlockPos feet);

        boolean stableBlockSupport(BlockPos feet);

        boolean safeWater(BlockPos feet);

        boolean destinationCollisionFree(BlockPos feet);
    }

    Optional<BlockPos> select(
            EgressProbe probe,
            BlockPos playerFeet,
            Vec3 boatCenter,
            BlockPos navigationTarget
    ) {
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(playerFeet, "playerFeet");
        Objects.requireNonNull(boatCenter, "boatCenter");
        Objects.requireNonNull(navigationTarget, "navigationTarget");

        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }

                BlockPos candidate = playerFeet.offset(dx, 0, dz);
                if (!probe.loaded(candidate)
                        || !probe.bodyClear(candidate)
                        || !probe.destinationCollisionFree(candidate)) {
                    continue;
                }

                boolean stableBlockSupport = probe.stableBlockSupport(candidate);
                boolean safeWater = probe.safeWater(candidate);
                if (!stableBlockSupport && !safeWater) {
                    continue;
                }

                double targetDistance = Math.hypot(
                        candidate.getX() - navigationTarget.getX(),
                        candidate.getZ() - navigationTarget.getZ()
                );
                double boatDistance = Math.hypot(
                        candidate.getX() + 0.5 - boatCenter.x,
                        candidate.getZ() + 0.5 - boatCenter.z
                );
                double waterOnlyPenalty = safeWater && !stableBlockSupport ? 2.0 : 0.0;
                double score = targetDistance - 0.75 * boatDistance + waterOnlyPenalty;

                if (best == null
                        || Double.compare(score, bestScore) < 0
                        || (Double.compare(score, bestScore) == 0 && comesBefore(candidate, best))) {
                    best = candidate;
                    bestScore = score;
                }
            }
        }

        return Optional.ofNullable(best);
    }

    private static boolean comesBefore(BlockPos candidate, BlockPos incumbent) {
        int xOrder = Integer.compare(candidate.getX(), incumbent.getX());
        return xOrder < 0 || xOrder == 0 && candidate.getZ() < incumbent.getZ();
    }
}
