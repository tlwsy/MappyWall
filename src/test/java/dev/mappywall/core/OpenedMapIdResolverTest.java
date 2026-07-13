package dev.mappywall.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpenedMapIdResolverTest {
    @Test
    void identifiesSelectedNewMapWithoutDependingOnClientPlaceholderCenter() {
        Optional<Integer> opened = new OpenedMapIdResolver().resolve(
                Set.of(3, 8),
                Set.of(3, 8, 21),
                21
        );

        assertEquals(Optional.of(21), opened);
    }

    @Test
    void refusesAmbiguousNewIdsWhenPendingSlotCannotDisambiguate() {
        Optional<Integer> opened = new OpenedMapIdResolver().resolve(
                Set.of(3),
                Set.of(3, 8, 21),
                null
        );

        assertEquals(Optional.empty(), opened);
    }

    @Test
    void refusesSoleNewIdWithoutExpectedSlotEvidence() {
        Optional<Integer> opened = new OpenedMapIdResolver().resolve(
                Set.of(3),
                Set.of(3, 99),
                null
        );

        assertEquals(Optional.empty(), opened);
    }
}
