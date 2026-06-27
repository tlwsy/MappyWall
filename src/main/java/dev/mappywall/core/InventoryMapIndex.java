package dev.mappywall.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class InventoryMapIndex {
    public BindingRepairResult repairManualOpenings(MapWallSave save, List<ObservedMap> observedMaps, Instant repairedAt) {
        Map<String, RouteStep> unboundByRegion = new HashMap<>();
        Set<String> boundRegions = new HashSet<>();
        Set<Integer> boundMapIds = new HashSet<>();

        for (MapBinding binding : save.bindings()) {
            boundRegions.add(binding.regionSignature());
            boundMapIds.add(binding.mapId());
        }

        for (RouteStep step : save.route()) {
            String signature = step.region().signature();
            if (!boundRegions.contains(signature)) {
                unboundByRegion.put(signature, step);
            }
        }

        List<MapBinding> repaired = new ArrayList<>(save.bindings());
        List<String> warnings = new ArrayList<>();

        for (ObservedMap observed : observedMaps) {
            String signature = observed.regionSignature();
            if (boundMapIds.contains(observed.mapId())) {
                continue;
            }

            if (boundRegions.contains(signature)) {
                boundMapIds.add(observed.mapId());
                continue;
            }

            RouteStep match = unboundByRegion.remove(signature);
            if (match == null) {
                continue;
            }

            repaired.add(new MapBinding(
                    match.wallPos(),
                    signature,
                    observed.mapId(),
                    repairedAt,
                    BindingVerification.MANUAL_REPAIR
            ));
            boundRegions.add(signature);
            boundMapIds.add(observed.mapId());
        }

        return new BindingRepairResult(repaired, warnings);
    }
}
