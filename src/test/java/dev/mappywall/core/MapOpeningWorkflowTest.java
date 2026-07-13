package dev.mappywall.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MapOpeningWorkflowTest {
    @Test
    void fourRegionRouteAdvancesOncePerMapDespiteUnreliablePlaceholderObservations() {
        MapWallPlanner planner = new MapWallPlanner();
        MapWallProject project = planner.createProject(
                "four-region-race", "local", "minecraft:overworld", 0, 4, 1, 0, 0, RunMode.AUTO_WALK
        );
        MapWallSave save = planner.createSave(project);
        Set<Integer> inventoryMapIds = new HashSet<>();
        OpenedMapIdResolver idResolver = new OpenedMapIdResolver();
        InventoryMapIndex mapIndex = new InventoryMapIndex();

        for (int routeIndex = 0; routeIndex < save.route().size(); routeIndex++) {
            RouteStep expectedTarget = save.route().get(routeIndex);
            Set<Integer> knownBeforeUse = Set.copyOf(inventoryMapIds);
            int newMapId = 40 + routeIndex;
            inventoryMapIds.add(newMapId);

            int openedMapId = idResolver.resolve(knownBeforeUse, inventoryMapIds, newMapId).orElseThrow();
            ObservedMap placeholder = new ObservedMap(
                    newMapId,
                    expectedTarget.region().dimension(),
                    0,
                    0,
                    0,
                    -1.0,
                    false
            );
            BindingRepairResult repair = mapIndex.repairManualOpenings(
                    save, List.of(placeholder), Instant.EPOCH.plusSeconds(routeIndex)
            );
            save = planner.reconcileBindings(save, repair.bindings());

            assertEquals(expectedTarget.region().signature(), planner.nextOpenStep(save).region().signature());
            save = planner.bindCurrentStep(
                    save,
                    openedMapId,
                    Instant.EPOCH.plusSeconds(routeIndex),
                    BindingVerification.POSITION_CAPTURE
            );
            assertEquals(
                    expectedTarget.region().signature(),
                    save.bindingForMapId(newMapId).orElseThrow().regionSignature()
            );
            if (routeIndex + 1 < save.route().size()) {
                assertEquals(
                        save.route().get(routeIndex + 1).region().signature(),
                        planner.nextOpenStep(save).region().signature()
                );
            }
        }

        assertEquals(ProjectStatus.COMPLETE, save.project().status());
        assertEquals(4, save.bindings().size());
    }
}
