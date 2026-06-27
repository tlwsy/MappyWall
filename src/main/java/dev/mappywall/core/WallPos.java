package dev.mappywall.core;

public record WallPos(int column, int row) {
    public WallPos {
        if (column < 0) {
            throw new IllegalArgumentException("column must be non-negative");
        }
        if (row < 0) {
            throw new IllegalArgumentException("row must be non-negative");
        }
    }
}

