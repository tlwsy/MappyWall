package dev.mappywall.core;

public record BlockTarget(int x, int z) {
    public double distanceSquaredTo(double playerX, double playerZ) {
        double dx = x - playerX;
        double dz = z - playerZ;
        return dx * dx + dz * dz;
    }
}

