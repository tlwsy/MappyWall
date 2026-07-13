package dev.mappywall.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ZoomedMapIdResolverTest {
    @Test
    void inheritsLineageForSingleNewMapAtExpectedScaleEvenWhenCenterIsUnavailable() {
        Optional<Integer> zoomed = new ZoomedMapIdResolver().resolve(
                Set.of(4, 9),
                List.of(new ObservedMap(12, "minecraft:overworld", 2, 0, 0, -1.0, false)),
                2
        );

        assertEquals(Optional.of(12), zoomed);
    }

    @Test
    void refusesAmbiguousNewMapsAtExpectedScale() {
        Optional<Integer> zoomed = new ZoomedMapIdResolver().resolve(
                Set.of(4),
                List.of(
                        new ObservedMap(12, "minecraft:overworld", 2, 0, 0, -1.0, false),
                        new ObservedMap(13, "minecraft:overworld", 2, 0, 0, -1.0, false)
                ),
                2
        );

        assertEquals(Optional.empty(), zoomed);
    }

    @Test
    void doesNotAdoptUnrelatedMapWhileCartographySourceStillExists() {
        PendingMapZoom pending = new PendingMapZoom(
                "minecraft:overworld|2|192|192",
                4,
                2,
                Set.of(4, 9),
                2,
                Instant.EPOCH
        );

        Optional<Integer> zoomed = new ZoomedMapIdResolver().resolve(
                pending,
                List.of(new ObservedMap(12, "minecraft:overworld", 2, 0, 0, -1.0, false)),
                2
        );

        assertEquals(Optional.empty(), zoomed);
    }

    @Test
    void adoptsOneOutputWhenACloneWithTheSameSourceIdStillExists() {
        PendingMapZoom pending = new PendingMapZoom(
                "minecraft:overworld|2|192|192",
                4,
                2,
                Set.of(4, 9),
                2,
                Instant.EPOCH
        );

        Optional<Integer> zoomed = new ZoomedMapIdResolver().resolve(
                pending,
                List.of(new ObservedMap(12, "minecraft:overworld", 2, 0, 0, -1.0, false)),
                1
        );

        assertEquals(Optional.of(12), zoomed);
    }

    @Test
    void adoptsUniqueNewOwnedIdBeforeItsMapStateArrives() {
        PendingMapZoom pending = new PendingMapZoom(
                "minecraft:overworld|2|192|192",
                4,
                2,
                Set.of(4, 9),
                1,
                Instant.EPOCH
        );

        Optional<Integer> zoomed = new ZoomedMapIdResolver().resolve(
                pending,
                Set.of(9, 12),
                0
        );

        assertEquals(Optional.of(12), zoomed);
    }

    @Test
    void refusesAmbiguousOwnedIdsWithoutMapState() {
        PendingMapZoom pending = new PendingMapZoom(
                "minecraft:overworld|2|192|192",
                4,
                2,
                Set.of(4),
                1,
                Instant.EPOCH
        );

        Optional<Integer> zoomed = new ZoomedMapIdResolver().resolve(
                pending,
                Set.of(12, 13),
                0
        );

        assertEquals(Optional.empty(), zoomed);
    }
}
