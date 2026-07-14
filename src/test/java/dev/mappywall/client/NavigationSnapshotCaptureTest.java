package dev.mappywall.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mappywall.client.LocalPathPlanner.Cell;
import dev.mappywall.client.LocalPathPlanner.NavigationSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class NavigationSnapshotCaptureTest {
    private static final int CAPTURE_WIDTH = 57;
    private static final int COLUMN_COUNT = CAPTURE_WIDTH * CAPTURE_WIDTH;

    @Test
    void copiesNoMoreThanTheRequestedColumnsPerTick() {
        CountingWorldView world = CountingWorldView.flat(64);
        NavigationSnapshotCapture capture = new NavigationSnapshotCapture(
                world,
                new BlockPos(0, 65, 0)
        );

        assertFalse(capture.advance(7));
        assertEquals(7, world.columnsRead());

        assertFalse(capture.advance(5));
        assertEquals(12, world.columnsRead());
    }

    @Test
    void finishedSnapshotUsesTheSeamAsItsStart() {
        BlockPos seam = new BlockPos(24, 65, -8);
        NavigationSnapshotCapture capture = new NavigationSnapshotCapture(
                CountingWorldView.flat(64),
                seam
        );

        while (!capture.advance(256)) {
            // Drain the deliberately bounded batches.
        }

        assertEquals(seam, capture.finish().start());
    }

    @Test
    void recordsSurfaceHeightAndUnloadedColumns() {
        CountingWorldView world = CountingWorldView.flat(70).withUnloadedColumn(2, 3);
        NavigationSnapshotCapture capture = new NavigationSnapshotCapture(
                world,
                new BlockPos(0, 71, 0)
        );

        while (!capture.advance(512)) {
            // Drain the deliberately bounded batches.
        }

        NavigationSnapshot snapshot = capture.finish();
        assertEquals(71, snapshot.surfaceHeight(0, 0));
        assertFalse(snapshot.isColumnLoaded(2, 3));
        assertFalse(snapshot.hasSurfaceHeight(2, 3));
    }

    @Test
    void readsExactBoundsInXMajorThenZMajorOrder() {
        BlockPos seam = new BlockPos(24, 65, -8);
        CountingWorldView world = CountingWorldView.flat(64).withWorldBounds(60, 72);
        NavigationSnapshotCapture capture = new NavigationSnapshotCapture(world, seam);

        assertTrue(capture.advance(COLUMN_COUNT));

        List<ColumnRead> expected = new ArrayList<>(COLUMN_COUNT);
        for (int x = -4; x <= 52; x++) {
            for (int z = -36; z <= 20; z++) {
                expected.add(new ColumnRead(x, z, 60, 72));
            }
        }
        assertEquals(expected, world.reads());

        NavigationSnapshot snapshot = capture.finish();
        assertEquals(-4, snapshot.minX());
        assertEquals(52, snapshot.maxX());
        assertEquals(-36, snapshot.minZ());
        assertEquals(20, snapshot.maxZ());
        assertEquals(60, snapshot.minY());
        assertEquals(72, snapshot.maxY());
        assertEquals(60, snapshot.bottomY());
        assertEquals(72, snapshot.topYInclusive());
    }

    @Test
    void usesConstructionTimeWorldMetadataForEveryColumnAndTheSnapshot() {
        CountingWorldView world = CountingWorldView.flat(64)
                .withWorldBounds(60, 72)
                .withSurfaceAware(true);
        NavigationSnapshotCapture capture = new NavigationSnapshotCapture(
                world,
                new BlockPos(0, 65, 0)
        );

        assertEquals(1, world.bottomYCalls());
        assertEquals(1, world.topYInclusiveCalls());
        assertEquals(1, world.surfaceAwareCalls());
        world.withWorldBounds(-64, 319).withSurfaceAware(false);

        assertTrue(capture.advance(COLUMN_COUNT));
        assertTrue(world.reads().stream().allMatch(read -> read.minY() == 60 && read.maxY() == 72));
        NavigationSnapshot snapshot = capture.finish();

        assertEquals(60, snapshot.bottomY());
        assertEquals(72, snapshot.topYInclusive());
        assertTrue(snapshot.surfaceAware());
        assertEquals(65, snapshot.surfaceHeight(0, 0));
        assertEquals(1, world.bottomYCalls());
        assertEquals(1, world.topYInclusiveCalls());
        assertEquals(1, world.surfaceAwareCalls());
    }

    @Test
    void finishDoesNotReadWorldMetadataAfterCaptureCompletes() {
        CountingWorldView world = CountingWorldView.flat(64);
        NavigationSnapshotCapture capture = new NavigationSnapshotCapture(
                world,
                new BlockPos(0, 65, 0)
        );
        assertTrue(capture.advance(COLUMN_COUNT));
        int metadataReadsAtCompletion = world.metadataReads();

        NavigationSnapshot first = capture.finish();
        assertSame(first, capture.finish());

        assertEquals(metadataReadsAtCompletion, world.metadataReads());
    }

    @Test
    void finishFailsUntilEveryColumnHasBeenCaptured() {
        NavigationSnapshotCapture capture = new NavigationSnapshotCapture(
                CountingWorldView.flat(64),
                new BlockPos(0, 65, 0)
        );

        assertThrows(IllegalStateException.class, capture::finish);
        assertFalse(capture.advance(COLUMN_COUNT - 1));
        assertThrows(IllegalStateException.class, capture::finish);
        assertTrue(capture.advance(1));
    }

    @Test
    void rejectsNonPositiveColumnBudgets() {
        NavigationSnapshotCapture capture = new NavigationSnapshotCapture(
                CountingWorldView.flat(64),
                new BlockPos(0, 65, 0)
        );

        assertThrows(IllegalArgumentException.class, () -> capture.advance(0));
        assertThrows(IllegalArgumentException.class, () -> capture.advance(-1));
    }

    @Test
    void freezesDefensiveCopiesOfCapturedData() {
        BlockPos floor = new BlockPos(0, 64, 0);
        Cell stone = new Cell(false, false, false, false, "minecraft:stone", true, true);
        Cell lava = new Cell(true, true, false, true, "minecraft:lava", true, false);
        CountingWorldView world = CountingWorldView.flat(64).withCell(floor, stone);
        NavigationSnapshotCapture capture = new NavigationSnapshotCapture(
                world,
                new BlockPos(0, 65, 0)
        );

        assertTrue(capture.advance(COLUMN_COUNT));
        NavigationSnapshot snapshot = capture.finish();
        world.withCell(floor, lava).withUnloadedColumn(0, 0).withSurfaceHeight(0, 0, 90);

        assertEquals(stone, snapshot.cell(floor));
        assertTrue(snapshot.isColumnLoaded(0, 0));
        assertEquals(65, snapshot.surfaceHeight(0, 0));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.cells().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.loadedColumns().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.surfaceHeights().clear());
    }

    @Test
    void completedCapturePerformsNoExtraReadsAndReusesItsSnapshot() {
        CountingWorldView world = CountingWorldView.flat(64);
        NavigationSnapshotCapture capture = new NavigationSnapshotCapture(
                world,
                new BlockPos(0, 65, 0)
        );

        assertTrue(capture.advance(COLUMN_COUNT));
        assertEquals(COLUMN_COUNT, world.columnsRead());
        NavigationSnapshot first = capture.finish();

        assertTrue(capture.advance(1));
        assertTrue(capture.advance(100));
        assertEquals(COLUMN_COUNT, world.columnsRead());
        assertSame(first, capture.finish());
    }

    private record ColumnRead(int x, int z, int minY, int maxY) {
    }

    private static final class CountingWorldView implements WorldView {
        private int bottomY = -64;
        private int topYInclusive = 319;
        private boolean surfaceAware = true;
        private int bottomYCalls;
        private int topYInclusiveCalls;
        private int surfaceAwareCalls;
        private final int defaultSurfaceHeight;
        private final List<ColumnRead> reads = new ArrayList<>();
        private final Set<Long> unloadedColumns = new HashSet<>();
        private final Map<Long, Integer> surfaceHeights = new HashMap<>();
        private final Map<Long, Map<Long, Cell>> cellsByColumn = new HashMap<>();

        private CountingWorldView(int floorY) {
            defaultSurfaceHeight = floorY + 1;
        }

        static CountingWorldView flat(int floorY) {
            return new CountingWorldView(floorY);
        }

        CountingWorldView withWorldBounds(int bottomY, int topYInclusive) {
            this.bottomY = bottomY;
            this.topYInclusive = topYInclusive;
            return this;
        }

        CountingWorldView withSurfaceAware(boolean surfaceAware) {
            this.surfaceAware = surfaceAware;
            return this;
        }

        CountingWorldView withUnloadedColumn(int x, int z) {
            unloadedColumns.add(columnKey(x, z));
            return this;
        }

        CountingWorldView withSurfaceHeight(int x, int z, int height) {
            surfaceHeights.put(columnKey(x, z), height);
            return this;
        }

        CountingWorldView withCell(BlockPos pos, Cell cell) {
            cellsByColumn
                    .computeIfAbsent(columnKey(pos.getX(), pos.getZ()), ignored -> new HashMap<>())
                    .put(pos.asLong(), cell);
            return this;
        }

        int columnsRead() {
            return reads.size();
        }

        List<ColumnRead> reads() {
            return List.copyOf(reads);
        }

        int bottomYCalls() {
            return bottomYCalls;
        }

        int topYInclusiveCalls() {
            return topYInclusiveCalls;
        }

        int surfaceAwareCalls() {
            return surfaceAwareCalls;
        }

        int metadataReads() {
            return bottomYCalls + topYInclusiveCalls + surfaceAwareCalls;
        }

        @Override
        public int bottomY() {
            bottomYCalls++;
            return bottomY;
        }

        @Override
        public int topYInclusive() {
            topYInclusiveCalls++;
            return topYInclusive;
        }

        @Override
        public boolean surfaceAware() {
            surfaceAwareCalls++;
            return surfaceAware;
        }

        @Override
        public ColumnData readColumn(int x, int z, int minY, int maxY) {
            reads.add(new ColumnRead(x, z, minY, maxY));
            long key = columnKey(x, z);
            if (unloadedColumns.contains(key)) {
                return new ColumnData(false, OptionalInt.empty(), Map.of());
            }
            return new ColumnData(
                    true,
                    OptionalInt.of(surfaceHeights.getOrDefault(key, defaultSurfaceHeight)),
                    cellsByColumn.getOrDefault(key, Map.of())
            );
        }

        private static long columnKey(int x, int z) {
            return ((long) x << 32) ^ (z & 0xffffffffL);
        }
    }
}
