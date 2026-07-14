package dev.mappywall.client;

import dev.mappywall.client.LocalPathPlanner.Cell;
import dev.mappywall.client.LocalPathPlanner.NavigationSnapshot;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

public final class NavigationSnapshotCapture {
    private static final int LOWER_HEADROOM_MARGIN = 2;
    private static final int UPPER_HEADROOM_MARGIN = 2;

    private final WorldView world;
    private final BlockPos seam;
    private final int bottomY;
    private final int topYInclusive;
    private final boolean surfaceAware;
    private final int minX;
    private final int maxX;
    private final int minY;
    private final int maxY;
    private final int minZ;
    private final int maxZ;
    private final Map<Long, Cell> cells = new HashMap<>();
    private final Set<Long> loadedColumns = new HashSet<>();
    private final Map<Long, Integer> surfaceHeights = new HashMap<>();

    private int nextX;
    private int nextZ;
    private boolean complete;
    private NavigationSnapshot finishedSnapshot;

    NavigationSnapshotCapture(WorldView world, BlockPos seam) {
        this.world = Objects.requireNonNull(world, "world");
        Objects.requireNonNull(seam, "seam");
        this.seam = new BlockPos(seam.getX(), seam.getY(), seam.getZ());
        bottomY = world.bottomY();
        topYInclusive = world.topYInclusive();
        surfaceAware = world.surfaceAware();
        minX = seam.getX() - LocalPathPlanner.MAX_HORIZONTAL_RANGE;
        maxX = seam.getX() + LocalPathPlanner.MAX_HORIZONTAL_RANGE;
        minZ = seam.getZ() - LocalPathPlanner.MAX_HORIZONTAL_RANGE;
        maxZ = seam.getZ() + LocalPathPlanner.MAX_HORIZONTAL_RANGE;
        minY = Math.max(
                bottomY,
                seam.getY()
                        - LocalPathPlanner.MAX_VERTICAL_RANGE
                        - LocalPathPlanner.MAX_DROP
                        - LOWER_HEADROOM_MARGIN
        );
        maxY = Math.min(
                topYInclusive,
                seam.getY() + LocalPathPlanner.MAX_VERTICAL_RANGE + UPPER_HEADROOM_MARGIN
        );
        nextX = minX;
        nextZ = minZ;
    }

    public NavigationSnapshotCapture(Level world, BlockPos seam) {
        this(new LevelWorldView(world), seam);
    }

    public boolean advance(int maxColumns) {
        if (maxColumns <= 0) {
            throw new IllegalArgumentException("maxColumns must be positive");
        }
        int processed = 0;
        while (!complete && processed < maxColumns) {
            captureColumn(nextX, nextZ);
            advanceCursor();
            processed++;
        }
        return complete;
    }

    public NavigationSnapshot finish() {
        if (!complete) {
            throw new IllegalStateException("Snapshot capture is not complete");
        }
        if (finishedSnapshot == null) {
            finishedSnapshot = new NavigationSnapshot(
                    seam,
                    minX,
                    maxX,
                    minY,
                    maxY,
                    minZ,
                    maxZ,
                    bottomY,
                    topYInclusive,
                    cells,
                    loadedColumns,
                    surfaceAware,
                    surfaceHeights
            );
        }
        return finishedSnapshot;
    }

    private void captureColumn(int x, int z) {
        ColumnData column = Objects.requireNonNull(
                world.readColumn(x, z, minY, maxY),
                "world.readColumn"
        );
        if (!column.loaded()) {
            return;
        }
        long key = columnKey(x, z);
        loadedColumns.add(key);
        cells.putAll(column.cells());
        if (surfaceAware && column.surfaceHeight().isPresent()) {
            surfaceHeights.put(key, column.surfaceHeight().getAsInt());
        }
    }

    private void advanceCursor() {
        if (nextZ < maxZ) {
            nextZ++;
            return;
        }
        if (nextX < maxX) {
            nextX++;
            nextZ = minZ;
            return;
        }
        complete = true;
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static final class LevelWorldView implements WorldView {
        private final Level world;
        private final boolean surfaceAware;
        private final BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        private LevelWorldView(Level world) {
            this.world = Objects.requireNonNull(world, "world");
            surfaceAware = world.dimensionType().hasSkyLight();
        }

        @Override
        public int bottomY() {
            return world.getMinY();
        }

        @Override
        public int topYInclusive() {
            return world.getMaxY() - 1;
        }

        @Override
        public boolean surfaceAware() {
            return surfaceAware;
        }

        @Override
        public ColumnData readColumn(int x, int z, int minY, int maxY) {
            mutable.set(x, minY, z);
            if (!world.hasChunkAt(mutable)) {
                return new ColumnData(false, OptionalInt.empty(), Map.of());
            }

            OptionalInt surfaceHeight = surfaceAware
                    ? OptionalInt.of(world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z))
                    : OptionalInt.empty();
            Map<Long, Cell> columnCells = new HashMap<>();
            for (int y = minY; y <= maxY; y++) {
                mutable.set(x, y, z);
                BlockState state = world.getBlockState(mutable);
                boolean water = world.getFluidState(mutable).is(FluidTags.WATER);
                boolean lava = world.getFluidState(mutable).is(FluidTags.LAVA);
                boolean passable = state.getCollisionShape(world, mutable).isEmpty();
                boolean replaceable = state.canBeReplaced();
                boolean breakable = !passable
                        && !water
                        && !lava
                        && !state.hasBlockEntity()
                        && state.getDestroySpeed(world, mutable) >= 0.0F;
                String blockId = state.isAir()
                        ? "minecraft:air"
                        : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                if (state.isAir() && !water && !lava) {
                    continue;
                }
                columnCells.put(mutable.asLong(), new Cell(
                        passable,
                        replaceable,
                        water,
                        lava,
                        blockId,
                        true,
                        breakable
                ));
            }
            return new ColumnData(true, surfaceHeight, columnCells);
        }
    }
}

interface WorldView {
    int bottomY();

    int topYInclusive();

    boolean surfaceAware();

    ColumnData readColumn(int x, int z, int minY, int maxY);
}

record ColumnData(boolean loaded, OptionalInt surfaceHeight, Map<Long, Cell> cells) {
    ColumnData {
        Objects.requireNonNull(surfaceHeight, "surfaceHeight");
        Objects.requireNonNull(cells, "cells");
    }
}
