package dev.mappywall.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class BoatEgressSelectorTest {
    private static final BlockPos PLAYER_FEET = new BlockPos(0, 64, 0);
    private static final Vec3 BOAT_CENTER = new Vec3(0.5, 64.0, 0.5);
    private static final BlockPos TARGET = new BlockPos(10, 64, 0);

    private final BoatEgressSelector selector = new BoatEgressSelector();

    @Test
    void evaluatesExactlyTheEightAdjacentSameHeightColumns() {
        RecordingProbe probe = new RecordingProbe();

        assertEquals(Optional.empty(), selector.select(probe, PLAYER_FEET, BOAT_CENTER, TARGET));

        assertEquals(Set.of(
                new BlockPos(-1, 64, -1),
                new BlockPos(-1, 64, 0),
                new BlockPos(-1, 64, 1),
                new BlockPos(0, 64, -1),
                new BlockPos(0, 64, 1),
                new BlockPos(1, 64, -1),
                new BlockPos(1, 64, 0),
                new BlockPos(1, 64, 1)
        ), probe.loadedCandidates);
        assertFalse(probe.loadedCandidates.contains(PLAYER_FEET));
        assertEquals(8, probe.loadedCalls);
    }

    @Test
    void rejectsAnUnloadedCandidateIndependently() {
        BlockPos rejected = new BlockPos(1, 64, 0);
        BlockPos fallback = new BlockPos(-1, 64, 0);
        RecordingProbe probe = new RecordingProbe()
                .candidate(rejected, Candidate.ground().withLoaded(false))
                .candidate(fallback, Candidate.ground());

        assertEquals(Optional.of(fallback), selector.select(probe, PLAYER_FEET, BOAT_CENTER, TARGET));
    }

    @Test
    void rejectsABodyBlockedCandidateIndependently() {
        BlockPos rejected = new BlockPos(1, 64, 0);
        BlockPos fallback = new BlockPos(-1, 64, 0);
        RecordingProbe probe = new RecordingProbe()
                .candidate(rejected, Candidate.ground().withBodyClear(false))
                .candidate(fallback, Candidate.ground());

        assertEquals(Optional.of(fallback), selector.select(probe, PLAYER_FEET, BOAT_CENTER, TARGET));
    }

    @Test
    void rejectsAnUnsupportedCandidateIndependently() {
        BlockPos rejected = new BlockPos(1, 64, 0);
        BlockPos fallback = new BlockPos(-1, 64, 0);
        RecordingProbe probe = new RecordingProbe()
                .candidate(rejected, Candidate.unsupported())
                .candidate(fallback, Candidate.ground());

        assertEquals(Optional.of(fallback), selector.select(probe, PLAYER_FEET, BOAT_CENTER, TARGET));
    }

    @Test
    void rejectsUnsafeFluidThatIsNotSafeWater() {
        BlockPos rejected = new BlockPos(1, 64, 0);
        RecordingProbe probe = new RecordingProbe()
                .candidate(rejected, Candidate.unsupported());

        assertEquals(Optional.empty(), selector.select(probe, PLAYER_FEET, BOAT_CENTER, TARGET));
    }

    @Test
    void rejectsAnOccupiedDestinationAabbIndependently() {
        BlockPos rejected = new BlockPos(1, 64, 0);
        BlockPos fallback = new BlockPos(-1, 64, 0);
        RecordingProbe probe = new RecordingProbe()
                .candidate(rejected, Candidate.ground().withDestinationCollisionFree(false))
                .candidate(fallback, Candidate.ground());

        assertEquals(Optional.of(fallback), selector.select(probe, PLAYER_FEET, BOAT_CENTER, TARGET));
    }

    @Test
    void prefersSafeGroundAwayFromBoatAndTowardTarget() {
        BlockPos towardTarget = new BlockPos(1, 64, 0);
        BlockPos behindBoat = new BlockPos(-1, 64, 0);
        RecordingProbe probe = new RecordingProbe()
                .candidate(towardTarget, Candidate.ground())
                .candidate(behindBoat, Candidate.ground());

        assertEquals(Optional.of(towardTarget), selector.select(probe, PLAYER_FEET, BOAT_CENTER, TARGET));
    }

    @Test
    void targetDistanceUsesUnsquaredHypot() {
        BlockPos expected = new BlockPos(0, 64, -1);
        BlockPos squaredDistanceAlternative = new BlockPos(-1, 64, -1);
        BlockPos target = new BlockPos(-8, 64, -8);
        Vec3 boatCenter = new Vec3(-3.0, 64.0, -1.0);
        RecordingProbe probe = new RecordingProbe()
                .candidate(expected, Candidate.ground())
                .candidate(squaredDistanceAlternative, Candidate.ground());

        assertEquals(Optional.of(expected), selector.select(probe, PLAYER_FEET, boatCenter, target));
    }

    @Test
    void boatDistanceUsesUnsquaredHypotWithSpecifiedWeight() {
        BlockPos expected = new BlockPos(-1, 64, -1);
        BlockPos squaredDistanceAlternative = new BlockPos(1, 64, 1);
        BlockPos target = new BlockPos(-8, 64, -8);
        Vec3 boatCenter = new Vec3(-3.0, 64.0, -3.0);
        RecordingProbe probe = new RecordingProbe()
                .candidate(expected, Candidate.ground())
                .candidate(squaredDistanceAlternative, Candidate.ground());

        assertEquals(Optional.of(expected), selector.select(probe, PLAYER_FEET, boatCenter, target));
    }

    @Test
    void boatDistanceWeightMustBeGreaterThanOneHalf() {
        BlockPos expected = new BlockPos(0, 64, -1);
        BlockPos lowWeightAlternative = new BlockPos(-1, 64, -1);
        BlockPos target = new BlockPos(-8, 64, -8);
        Vec3 boatCenter = new Vec3(-4.0, 64.0, -1.0);
        RecordingProbe probe = new RecordingProbe()
                .candidate(expected, Candidate.ground())
                .candidate(lowWeightAlternative, Candidate.ground());

        assertEquals(Optional.of(expected), selector.select(probe, PLAYER_FEET, boatCenter, target));
    }

    @Test
    void boatDistanceWeightMustBeLessThanOne() {
        BlockPos expected = new BlockPos(-1, 64, -1);
        BlockPos highWeightAlternative = new BlockPos(-1, 64, 1);
        BlockPos target = new BlockPos(-8, 64, -8);
        Vec3 boatCenter = new Vec3(-4.0, 64.0, -4.0);
        RecordingProbe probe = new RecordingProbe()
                .candidate(expected, Candidate.ground())
                .candidate(highWeightAlternative, Candidate.ground());

        assertEquals(Optional.of(expected), selector.select(probe, PLAYER_FEET, boatCenter, target));
    }

    @Test
    void safeWaterLosesToBetterGroundButIsSelectedWhenGroundIsUnavailable() {
        BlockPos water = new BlockPos(1, 64, 0);
        BlockPos ground = new BlockPos(0, 64, -1);
        RecordingProbe withGround = new RecordingProbe()
                .candidate(water, Candidate.water())
                .candidate(ground, Candidate.ground());

        assertEquals(Optional.of(ground), selector.select(withGround, PLAYER_FEET, BOAT_CENTER, TARGET));

        RecordingProbe waterOnly = new RecordingProbe().candidate(water, Candidate.water());
        assertEquals(Optional.of(water), selector.select(waterOnly, PLAYER_FEET, BOAT_CENTER, TARGET));
    }

    @Test
    void stableSupportThatAlsoContainsSafeWaterDoesNotReceiveWaterPenalty() {
        BlockPos wetGround = new BlockPos(-1, 64, 0);
        BlockPos dryGround = new BlockPos(1, 64, 0);
        RecordingProbe probe = new RecordingProbe()
                .candidate(wetGround, Candidate.groundAndWater())
                .candidate(dryGround, Candidate.ground());

        assertEquals(Optional.of(wetGround), selector.select(probe, PLAYER_FEET, BOAT_CENTER, PLAYER_FEET));
    }

    @Test
    void rejectsWaterWhenItsDestinationAabbIntersectsAnEntity() {
        BlockPos occupiedWater = new BlockPos(1, 64, 0);
        RecordingProbe probe = new RecordingProbe()
                .candidate(occupiedWater, Candidate.water().withDestinationCollisionFree(false));

        assertEquals(Optional.empty(), selector.select(probe, PLAYER_FEET, BOAT_CENTER, TARGET));
    }

    @Test
    void exactScoreTieUsesLowerXThenLowerZ() {
        BlockPos west = new BlockPos(-1, 64, 0);
        BlockPos east = new BlockPos(1, 64, 0);
        BlockPos north = new BlockPos(0, 64, -1);
        BlockPos south = new BlockPos(0, 64, 1);
        BlockPos centeredTarget = PLAYER_FEET;

        RecordingProbe xTie = new RecordingProbe()
                .candidate(east, Candidate.ground())
                .candidate(west, Candidate.ground());
        assertEquals(Optional.of(west), selector.select(xTie, PLAYER_FEET, BOAT_CENTER, centeredTarget));

        RecordingProbe zTie = new RecordingProbe()
                .candidate(south, Candidate.ground())
                .candidate(north, Candidate.ground());
        assertEquals(Optional.of(north), selector.select(zTie, PLAYER_FEET, BOAT_CENTER, centeredTarget));
    }

    @Test
    void returnsEmptyWhenAllEightCandidatesAreUnsafe() {
        RecordingProbe probe = new RecordingProbe();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    probe.candidate(PLAYER_FEET.offset(dx, 0, dz), Candidate.unsupported());
                }
            }
        }

        assertEquals(Optional.empty(), selector.select(probe, PLAYER_FEET, BOAT_CENTER, TARGET));
    }

    @Test
    void selectorRetainsNoProbeOrSelectionStateAcrossCalls() {
        assertEquals(0, BoatEgressSelector.class.getDeclaredFields().length);

        BlockPos west = new BlockPos(-1, 64, 0);
        BlockPos east = new BlockPos(1, 64, 0);
        RecordingProbe westOnly = new RecordingProbe().candidate(west, Candidate.ground());
        RecordingProbe eastOnly = new RecordingProbe().candidate(east, Candidate.ground());

        assertEquals(Optional.of(west), selector.select(westOnly, PLAYER_FEET, BOAT_CENTER, TARGET));
        assertEquals(Optional.of(east), selector.select(eastOnly, PLAYER_FEET, BOAT_CENTER, TARGET));
        assertEquals(Optional.of(west), selector.select(westOnly, PLAYER_FEET, BOAT_CENTER, TARGET));
    }

    @Test
    void validatesEveryArgument() {
        RecordingProbe probe = new RecordingProbe();

        assertThrows(NullPointerException.class, () -> selector.select(null, PLAYER_FEET, BOAT_CENTER, TARGET));
        assertThrows(NullPointerException.class, () -> selector.select(probe, null, BOAT_CENTER, TARGET));
        assertThrows(NullPointerException.class, () -> selector.select(probe, PLAYER_FEET, null, TARGET));
        assertThrows(NullPointerException.class, () -> selector.select(probe, PLAYER_FEET, BOAT_CENTER, null));
    }

    private record Candidate(
            boolean loaded,
            boolean bodyClear,
            boolean stableBlockSupport,
            boolean safeWater,
            boolean destinationCollisionFree
    ) {
        private static Candidate ground() {
            return new Candidate(true, true, true, false, true);
        }

        private static Candidate water() {
            return new Candidate(true, true, false, true, true);
        }

        private static Candidate groundAndWater() {
            return new Candidate(true, true, true, true, true);
        }

        private static Candidate unsupported() {
            return new Candidate(true, true, false, false, true);
        }

        private Candidate withLoaded(boolean value) {
            return new Candidate(value, bodyClear, stableBlockSupport, safeWater, destinationCollisionFree);
        }

        private Candidate withBodyClear(boolean value) {
            return new Candidate(loaded, value, stableBlockSupport, safeWater, destinationCollisionFree);
        }

        private Candidate withDestinationCollisionFree(boolean value) {
            return new Candidate(loaded, bodyClear, stableBlockSupport, safeWater, value);
        }
    }

    private static final class RecordingProbe implements BoatEgressSelector.EgressProbe {
        private final Map<BlockPos, Candidate> candidates = new HashMap<>();
        private final Set<BlockPos> loadedCandidates = new HashSet<>();
        private int loadedCalls;

        private RecordingProbe candidate(BlockPos position, Candidate candidate) {
            candidates.put(position, candidate);
            return this;
        }

        @Override
        public boolean loaded(BlockPos feet) {
            loadedCalls++;
            loadedCandidates.add(feet.immutable());
            return candidate(feet).loaded();
        }

        @Override
        public boolean bodyClear(BlockPos feet) {
            return candidate(feet).bodyClear();
        }

        @Override
        public boolean stableBlockSupport(BlockPos feet) {
            return candidate(feet).stableBlockSupport();
        }

        @Override
        public boolean safeWater(BlockPos feet) {
            return candidate(feet).safeWater();
        }

        @Override
        public boolean destinationCollisionFree(BlockPos feet) {
            return candidate(feet).destinationCollisionFree();
        }

        private Candidate candidate(BlockPos feet) {
            return candidates.getOrDefault(feet, new Candidate(false, false, false, false, false));
        }
    }
}
