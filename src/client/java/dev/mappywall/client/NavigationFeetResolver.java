package dev.mappywall.client;

import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

final class NavigationFeetResolver {
    static final double FEET_EPSILON = 1.0e-4;

    BlockPos resolve(LocalPlayer player) {
        return resolveProjected(player, player.getX(), player.getZ());
    }

    double resolveWaypointY(LocalPlayer player) {
        double physicalFeetY = player.getY();
        return selectWaypointY(physicalFeetY, resolve(player));
    }

    BlockPos resolveProjected(LocalPlayer player, double x, double z) {
        Level level = player.level();
        boolean groundedOnBlock = isNormalizationEligible(
                level != null,
                player.onGround(),
                player.isPassenger(),
                player.isInWater(),
                player.isSwimming(),
                player.onClimbable()
        );
        return resolve(
                x,
                player.getY(),
                z,
                groundedOnBlock,
                support -> level != null
                        && level.isLoaded(support)
                        && !level.getBlockState(support).getCollisionShape(level, support).isEmpty()
        );
    }

    static boolean isNormalizationEligible(
            boolean hasLevel,
            boolean onGround,
            boolean passenger,
            boolean inWater,
            boolean swimming,
            boolean climbing
    ) {
        return hasLevel
                && onGround
                && !passenger
                && !inWater
                && !swimming
                && !climbing;
    }

    BlockPos resolve(
            double x,
            double physicalFeetY,
            double z,
            boolean groundedOnBlock,
            Predicate<BlockPos> hasBlockCollision
    ) {
        BlockPos raw = BlockPos.containing(x, physicalFeetY, z);
        if (!groundedOnBlock) {
            return raw;
        }
        int candidateY = Mth.ceil(physicalFeetY - FEET_EPSILON);
        if (candidateY == raw.getY()) {
            return raw;
        }
        BlockPos candidate = new BlockPos(raw.getX(), candidateY, raw.getZ());
        return hasBlockCollision.test(candidate.below()) ? candidate : raw;
    }

    double resolveWaypointY(
            double x,
            double physicalFeetY,
            double z,
            boolean groundedOnBlock,
            Predicate<BlockPos> hasBlockCollision
    ) {
        BlockPos resolvedFeet = resolve(
                x,
                physicalFeetY,
                z,
                groundedOnBlock,
                hasBlockCollision
        );
        return selectWaypointY(physicalFeetY, resolvedFeet);
    }

    private double selectWaypointY(double physicalFeetY, BlockPos resolvedFeet) {
        int rawY = Mth.floor(physicalFeetY);
        return resolvedFeet.getY() == rawY ? physicalFeetY : resolvedFeet.getY();
    }
}
