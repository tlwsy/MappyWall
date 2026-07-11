package dev.mappywall.core;

public final class MapRegionMath {
    public static final int MIN_SCALE = 0;
    public static final int MAX_SCALE = 4;
    private static final int MAP_BASE_SIDE = 128;
    private static final int MAP_CENTER_OFFSET = 64;

    private MapRegionMath() {
    }

    public static void validateScale(int scale) {
        if (scale < MIN_SCALE || scale > MAX_SCALE) {
            throw new IllegalArgumentException("scale must be between 0 and 4");
        }
    }

    public static int sideBlocks(int scale) {
        validateScale(scale);
        return MAP_BASE_SIDE << scale;
    }

    public static int gridCoordinateForBlock(int blockCoordinate, int scale) {
        int side = sideBlocks(scale);
        return Math.toIntExact(Math.floorDiv((long) blockCoordinate + MAP_CENTER_OFFSET, side));
    }

    public static MapRegion regionForBlock(String dimension, int scale, double blockX, double blockZ) {
        int gridX = gridCoordinateForBlock(floorBlock(blockX), scale);
        int gridZ = gridCoordinateForBlock(floorBlock(blockZ), scale);
        return regionForGrid(dimension, scale, gridX, gridZ);
    }

    public static MapRegion offset(MapRegion anchor, int columnsEast, int rowsSouth) {
        return regionForGrid(
                anchor.dimension(),
                anchor.scale(),
                checkedCoordinate((long) anchor.gridX() + columnsEast, "grid X"),
                checkedCoordinate((long) anchor.gridZ() + rowsSouth, "grid Z")
        );
    }

    public static MapRegion regionForGrid(String dimension, int scale, int gridX, int gridZ) {
        int side = sideBlocks(scale);
        int centerX = checkedCoordinate((long) gridX * side + side / 2 - MAP_CENTER_OFFSET, "map center X");
        int centerZ = checkedCoordinate((long) gridZ * side + side / 2 - MAP_CENTER_OFFSET, "map center Z");
        int half = side / 2;
        MapBounds bounds = new MapBounds(
                checkedCoordinate((long) centerX - half, "map minimum X"),
                checkedCoordinate((long) centerZ - half, "map minimum Z"),
                checkedCoordinate((long) centerX + half - 1, "map maximum X"),
                checkedCoordinate((long) centerZ + half - 1, "map maximum Z")
        );
        return new MapRegion(dimension, scale, gridX, gridZ, centerX, centerZ, bounds);
    }

    private static int floorBlock(double value) {
        if (!Double.isFinite(value) || value < Integer.MIN_VALUE || value >= (double) Integer.MAX_VALUE + 1.0) {
            throw new IllegalArgumentException("block coordinate must be a finite 32-bit value");
        }
        return (int) Math.floor(value);
    }

    private static int checkedCoordinate(long value, String name) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " is outside the supported coordinate range");
        }
        return (int) value;
    }
}
