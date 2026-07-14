package dev.mappywall.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mappywall.core.PathSegmentCoordinator.Anchor;
import java.util.List;
import org.junit.jupiter.api.Test;

class NavigationTerrainProbeTest {
    @Test
    void probesPositiveCardinalDirectionAtOneBlockEuclideanIntervals() {
        Anchor seam = new Anchor(10, 64, -2);

        assertEquals(
                List.of(
                        seam,
                        new Anchor(11, 64, -2),
                        new Anchor(12, 64, -2),
                        new Anchor(13, 64, -2),
                        new Anchor(14, 64, -2)
                ),
                NavigationTerrainProbe.targetDirectedColumns(
                        seam,
                        new Anchor(30, 90, -2),
                        4
                )
        );
    }

    @Test
    void probesNegativeCardinalDirection() {
        Anchor seam = new Anchor(10, 64, -2);

        assertEquals(
                List.of(
                        seam,
                        new Anchor(9, 64, -2),
                        new Anchor(8, 64, -2),
                        new Anchor(7, 64, -2),
                        new Anchor(6, 64, -2)
                ),
                NavigationTerrainProbe.targetDirectedColumns(
                        seam,
                        new Anchor(-30, 12, -2),
                        4
                )
        );
    }

    @Test
    void diagonalRoundingIsOrderedAndDeduplicated() {
        Anchor seam = new Anchor(0, 64, 0);

        assertEquals(
                List.of(
                        seam,
                        new Anchor(1, 64, 1),
                        new Anchor(2, 64, 2),
                        new Anchor(3, 64, 3)
                ),
                NavigationTerrainProbe.targetDirectedColumns(
                        seam,
                        new Anchor(10, 64, 10),
                        4
                )
        );
    }

    @Test
    void stopsAtATargetCloserThanFourBlocks() {
        Anchor seam = new Anchor(-2, 70, 5);

        assertEquals(
                List.of(
                        seam,
                        new Anchor(-1, 70, 5),
                        new Anchor(0, 70, 5)
                ),
                NavigationTerrainProbe.targetDirectedColumns(
                        seam,
                        new Anchor(0, 100, 5),
                        4
                )
        );
    }

    @Test
    void zeroHorizontalDistanceReturnsOnlyTheSeam() {
        Anchor seam = new Anchor(3, 64, -7);

        assertEquals(
                List.of(seam),
                NavigationTerrainProbe.targetDirectedColumns(
                        seam,
                        new Anchor(3, 120, -7),
                        4
                )
        );
    }

    @Test
    void resultIsImmutableAndProbeCountIsCappedAtFour() {
        List<Anchor> columns = NavigationTerrainProbe.targetDirectedColumns(
                new Anchor(0, 64, 0),
                new Anchor(100, 64, 0),
                40
        );

        assertEquals(5, columns.size());
        assertThrows(UnsupportedOperationException.class,
                () -> columns.add(new Anchor(5, 64, 0)));
    }

    @Test
    void rejectsNullInputsAndNonPositiveDistance() {
        Anchor anchor = new Anchor(0, 64, 0);

        assertThrows(NullPointerException.class,
                () -> NavigationTerrainProbe.targetDirectedColumns(null, anchor, 4));
        assertThrows(NullPointerException.class,
                () -> NavigationTerrainProbe.targetDirectedColumns(anchor, null, 4));
        assertThrows(IllegalArgumentException.class,
                () -> NavigationTerrainProbe.targetDirectedColumns(anchor, anchor, 0));
        assertThrows(IllegalArgumentException.class,
                () -> NavigationTerrainProbe.targetDirectedColumns(anchor, anchor, -1));
    }
}
