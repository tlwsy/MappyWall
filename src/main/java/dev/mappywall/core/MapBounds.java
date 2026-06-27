package dev.mappywall.core;

public record MapBounds(int minX, int minZ, int maxX, int maxZ) {
    public MapBounds {
        if (maxX < minX) {
            throw new IllegalArgumentException("maxX must be >= minX");
        }
        if (maxZ < minZ) {
            throw new IllegalArgumentException("maxZ must be >= minZ");
        }
    }

    public boolean contains(double x, double z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }
}

