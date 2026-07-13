package dev.mappywall.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class MapCandidateSelectorTest {
    private final MapCandidateSelector selector = new MapCandidateSelector();

    @Test
    void higherScaleOutputWinsOverLowerScaleMapStillInCartographyInput() {
        ObservedMap lowerInput = observed(4, 0);
        ObservedMap higherOutput = observed(12, 1);

        ObservedMap selected = selector.selectHighestScale(
                List.of(lowerInput, higherOutput),
                lowerInput.mapId(),
                null
        ).orElseThrow();

        assertEquals(higherOutput, selected);
    }

    @Test
    void cartographyInputBreaksATieOnlyAfterScale() {
        ObservedMap inventoryMap = observed(4, 1);
        ObservedMap tableMap = observed(12, 1);

        ObservedMap selected = selector.selectHighestScale(
                List.of(inventoryMap, tableMap),
                tableMap.mapId(),
                inventoryMap.mapId()
        ).orElseThrow();

        assertEquals(tableMap, selected);
    }

    private ObservedMap observed(int mapId, int scale) {
        return new ObservedMap(mapId, "minecraft:overworld", scale, 0, 0, 0.5, false);
    }
}
