package dev.mappywall.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class WaterRouteEvidenceAdapterTest {
    @Test
    void submergedWaypointResolvesToTopOfLoadedWaterColumn() {
        BlockPos waypoint = new BlockPos(2, 60, 3);
        Set<BlockPos> water = Set.of(waypoint, waypoint.above(), waypoint.above(2));
        Optional<WaterRouteEvidenceAdapter.ResolvedSurface> resolved =
                WaterRouteEvidenceAdapter.resolveBoatableSurface(
                        waypoint,
                        80,
                        probe(water, Set.of(), Set.of())
                );
        assertEquals(waypoint.above(2), resolved.orElseThrow().waterPos());
        assertEquals(waypoint, resolved.orElseThrow().routePos());
    }

    @Test
    void unloadedOrCoveredSurfaceIsRejected() {
        BlockPos waypoint = new BlockPos(2, 60, 3);
        assertTrue(WaterRouteEvidenceAdapter.resolveBoatableSurface(
                waypoint, 80, probe(Set.of(waypoint), Set.of(), Set.of(waypoint))).isEmpty());
        assertTrue(WaterRouteEvidenceAdapter.resolveBoatableSurface(
                waypoint, 80, probe(Set.of(waypoint), Set.of(), Set.of(waypoint.above()))).isEmpty());
    }

    @Test
    void nonWaterAndBoatEnvelopeObstructionAreRejected() {
        BlockPos waypoint = new BlockPos(2, 60, 3);
        assertTrue(WaterRouteEvidenceAdapter.resolveBoatableSurface(
                waypoint, 80, probe(Set.of(), Set.of(), Set.of())).isEmpty());
        assertTrue(WaterRouteEvidenceAdapter.resolveBoatableSurface(
                waypoint, 80, probe(Set.of(waypoint), Set.of(waypoint), Set.of())).isEmpty());

        AABB envelope = WaterRouteEvidenceAdapter.boatEnvelope(waypoint);
        assertTrue(envelope.minX < waypoint.getX());
        assertTrue(envelope.maxX > waypoint.getX() + 1.0);
        assertTrue(envelope.minZ < waypoint.getZ());
        assertTrue(envelope.maxZ > waypoint.getZ() + 1.0);
        assertEquals(waypoint.getY() + 1.0, envelope.minY, 1.0e-9);
    }

    @Test
    void waterColumnWithoutLoadedAirBeforeMaxYIsRejected() {
        BlockPos waypoint = new BlockPos(2, 60, 3);
        assertTrue(WaterRouteEvidenceAdapter.resolveBoatableSurface(
                waypoint,
                62,
                probe(Set.of(waypoint, waypoint.above(), waypoint.above(2)), Set.of(), Set.of())
        ).isEmpty());
    }

    @Test
    void liveEnvelopeProofIgnoresEntitiesButStillRejectsBlocks() throws IOException {
        Path sourcePath = Path.of(
                "src", "client", "java", "dev", "mappywall", "client",
                "WaterRouteEvidenceAdapter.java");
        String source = Files.exists(sourcePath) ? Files.readString(sourcePath) : "";
        String compact = source.replaceAll("\\s+", "");
        assertTrue(compact.contains("client.level.noBlockCollision(null,envelope)"));
        assertFalse(compact.contains("client.level.noCollision(null,envelope)"));
    }

    @Test
    void acceptedContinuousStepsProveCardinalAndDiagonalDistance() {
        List<LocalPathPlanner.PathStep> steps = List.of(
                swim(1, 62, 0), swim(2, 62, 0), swim(3, 62, 1));
        WaterRouteEvidenceAdapter.Evidence evidence = WaterRouteEvidenceAdapter.collect(
                new BlockPos(0, 64, 0),
                steps,
                12.0,
                step -> Optional.of(surface(step, 62))
        );
        assertEquals(2.0 + Math.sqrt(2.0), evidence.futureConfirmedDistanceBlocks(), 1.0e-9);
        assertEquals(62, evidence.surfaceY().orElseThrow());
    }

    @Test
    void nonSwimMissingAndDifferentHeightSurfacesTerminateEvidence() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        List<LocalPathPlanner.PathStep> withWalk = List.of(
                swim(1, 62, 0), walk(2, 62, 0), swim(3, 62, 0));
        WaterRouteEvidenceAdapter.Evidence stoppedAtWalk = WaterRouteEvidenceAdapter.collect(
                anchor, withWalk, 12.0, step -> Optional.of(surface(step, 62)));
        assertEquals(1.0, stoppedAtWalk.futureConfirmedDistanceBlocks(), 1.0e-9);
        assertEquals(1, stoppedAtWalk.surfaces().size());

        List<LocalPathPlanner.PathStep> twoSwims = List.of(swim(1, 62, 0), swim(2, 62, 0));
        WaterRouteEvidenceAdapter.Evidence stoppedAtMissing = WaterRouteEvidenceAdapter.collect(
                anchor,
                twoSwims,
                12.0,
                step -> step.pos().getX() == 1
                        ? Optional.of(surface(step, 62))
                        : Optional.empty()
        );
        assertEquals(1.0, stoppedAtMissing.futureConfirmedDistanceBlocks(), 1.0e-9);
        assertEquals(1, stoppedAtMissing.surfaces().size());

        WaterRouteEvidenceAdapter.Evidence stoppedAtHeightChange = WaterRouteEvidenceAdapter.collect(
                anchor,
                twoSwims,
                12.0,
                step -> Optional.of(surface(step, step.pos().getX() == 1 ? 62 : 63))
        );
        assertEquals(1.0, stoppedAtHeightChange.futureConfirmedDistanceBlocks(), 1.0e-9);
        assertEquals(62, stoppedAtHeightChange.surfaceY().orElseThrow());
        assertEquals(1, stoppedAtHeightChange.surfaces().size());
    }

    @Test
    void repeatedOrSkippedRouteCellsTerminateEvidence() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        WaterRouteEvidenceAdapter.Evidence repeated = WaterRouteEvidenceAdapter.collect(
                anchor,
                List.of(swim(1, 62, 0), swim(1, 62, 0), swim(2, 62, 0)),
                12.0,
                step -> Optional.of(surface(step, 62))
        );
        assertEquals(1.0, repeated.futureConfirmedDistanceBlocks(), 1.0e-9);
        assertEquals(1, repeated.surfaces().size());

        WaterRouteEvidenceAdapter.Evidence skipped = WaterRouteEvidenceAdapter.collect(
                anchor,
                List.of(swim(1, 62, 0), swim(3, 62, 0), swim(4, 62, 0)),
                12.0,
                step -> Optional.of(surface(step, 62))
        );
        assertEquals(1.0, skipped.futureConfirmedDistanceBlocks(), 1.0e-9);
        assertEquals(1, skipped.surfaces().size());
    }

    @Test
    void proofLimitStopsLookupWithoutReadingTheUnneededSuffix() {
        List<LocalPathPlanner.PathStep> steps = IntStream.rangeClosed(1, 12)
                .mapToObj(x -> swim(x, 62, 0))
                .toList();
        AtomicInteger lookups = new AtomicInteger();
        WaterRouteEvidenceAdapter.Evidence evidence = WaterRouteEvidenceAdapter.collect(
                new BlockPos(0, 64, 0),
                steps,
                4.0,
                step -> {
                    lookups.incrementAndGet();
                    return Optional.of(surface(step, 62));
                }
        );
        assertEquals(4.0, evidence.futureConfirmedDistanceBlocks(), 1.0e-9);
        assertEquals(4, evidence.surfaces().size());
        assertEquals(4, lookups.get());
    }

    @Test
    void invalidProofLimitsAreRejected() {
        List<LocalPathPlanner.PathStep> steps = List.of(swim(1, 62, 0));
        assertThrows(IllegalArgumentException.class, () -> WaterRouteEvidenceAdapter.collect(
                new BlockPos(0, 64, 0), steps, 0.0, step -> Optional.of(surface(step, 62))));
        assertThrows(IllegalArgumentException.class, () -> WaterRouteEvidenceAdapter.collect(
                new BlockPos(0, 64, 0), steps, Double.NaN, step -> Optional.of(surface(step, 62))));
        assertThrows(IllegalArgumentException.class, () -> WaterRouteEvidenceAdapter.collect(
                new BlockPos(0, 64, 0), steps, Double.POSITIVE_INFINITY,
                step -> Optional.of(surface(step, 62))));
    }

    @Test
    void activeAndAcceptedContinuousStepsFormOneEvidenceSequence() {
        List<LocalPathPlanner.PathStep> active = List.of(swim(1, 62, 0), swim(2, 62, 0));
        List<LocalPathPlanner.PathStep> continuous = List.of(swim(3, 62, 0), swim(4, 62, 0));
        ArrayList<LocalPathPlanner.PathStep> preview = new ArrayList<>(active);
        preview.addAll(continuous);
        WaterRouteEvidenceAdapter.Evidence evidence = WaterRouteEvidenceAdapter.collect(
                new BlockPos(0, 64, 0),
                preview,
                12.0,
                step -> Optional.of(surface(step, 62))
        );
        assertEquals(4.0, evidence.futureConfirmedDistanceBlocks(), 1.0e-9);
        assertEquals(4, evidence.surfaces().size());
    }

    @Test
    void evidenceDefensivelyCopiesSurfaces() {
        ArrayList<WaterRouteEvidenceAdapter.ResolvedSurface> surfaces = new ArrayList<>();
        surfaces.add(new WaterRouteEvidenceAdapter.ResolvedSurface(
                new BlockPos(1, 60, 0), new BlockPos(1, 62, 0)));

        WaterRouteEvidenceAdapter.Evidence evidence = new WaterRouteEvidenceAdapter.Evidence(
                java.util.OptionalInt.of(62), 1.0, surfaces);
        surfaces.clear();

        assertEquals(1, evidence.surfaces().size());
        assertThrows(UnsupportedOperationException.class, () -> evidence.surfaces().clear());
    }

    private static LocalPathPlanner.PathStep swim(int x, int y, int z) {
        return new LocalPathPlanner.PathStep(
                new BlockPos(x, y, z), LocalPathPlanner.StepAction.SWIM, null);
    }

    private static LocalPathPlanner.PathStep walk(int x, int y, int z) {
        return new LocalPathPlanner.PathStep(
                new BlockPos(x, y, z), LocalPathPlanner.StepAction.WALK, null);
    }

    private static WaterRouteEvidenceAdapter.ResolvedSurface surface(
            LocalPathPlanner.PathStep step,
            int surfaceY
    ) {
        return new WaterRouteEvidenceAdapter.ResolvedSurface(
                step.pos(),
                new BlockPos(step.pos().getX(), surfaceY, step.pos().getZ())
        );
    }

    private static WaterRouteEvidenceAdapter.SurfaceProbe probe(
            Set<BlockPos> water,
            Set<BlockPos> blockedEnvelopes,
            Set<BlockPos> unloaded
    ) {
        return new WaterRouteEvidenceAdapter.SurfaceProbe() {
            @Override
            public boolean loaded(BlockPos pos) {
                return !unloaded.contains(pos);
            }

            @Override
            public boolean water(BlockPos pos) {
                return water.contains(pos);
            }

            @Override
            public boolean boatEnvelopeClear(BlockPos waterSurface) {
                return !blockedEnvelopes.contains(waterSurface);
            }
        };
    }
}
