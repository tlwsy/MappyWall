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
        return Math.floorDiv(blockCoordinate + MAP_CENTER_OFFSET, side);
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
                anchor.gridX() + columnsEast,
                anchor.gridZ() + rowsSouth
        );
    }

    public static MapRegion regionForGrid(String dimension, int scale, int gridX, int gridZ) {
        int side = sideBlocks(scale);
        int centerX = gridX * side + side / 2 - MAP_CENTER_OFFSET;
        int centerZ = gridZ * side + side / 2 - MAP_CENTER_OFFSET;
        int half = side / 2;
        MapBounds bounds = new MapBounds(centerX - half, centerZ - half, centerX + half - 1, centerZ + half - 1);
        return new MapRegion(dimension, scale, gridX, gridZ, centerX, centerZ, bounds);
    }

    private static int floorBlock(double value) {
        return (int) Math.floor(value);
    }
}

