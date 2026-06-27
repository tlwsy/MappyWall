package dev.mappywall.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MapRegionMathTest {
    @Test
    void sideBlocksMatchVanillaScaleSizes() {
        assertEquals(128, MapRegionMath.sideBlocks(0));
        assertEquals(256, MapRegionMath.sideBlocks(1));
        assertEquals(512, MapRegionMath.sideBlocks(2));
        assertEquals(1024, MapRegionMath.sideBlocks(3));
        assertEquals(2048, MapRegionMath.sideBlocks(4));
    }

    @Test
    void scaleZeroBoundaryMovesAtPositiveSixtyFour() {
        MapRegion origin = MapRegionMath.regionForBlock("minecraft:overworld", 0, 63.99, 0);
        MapRegion east = MapRegionMath.regionForBlock("minecraft:overworld", 0, 64, 0);

        assertEquals(0, origin.centerX());
        assertEquals(128, east.centerX());
        assertTrue(origin.bounds().contains(63, 0));
        assertTrue(east.bounds().contains(64, 0));
    }

    @Test
    void negativeBoundaryUsesFloorDivision() {
        MapRegion west = MapRegionMath.regionForBlock("minecraft:overworld", 0, -65, 0);
        MapRegion origin = MapRegionMath.regionForBlock("minecraft:overworld", 0, -64, 0);

        assertEquals(-128, west.centerX());
        assertEquals(0, origin.centerX());
    }
}

