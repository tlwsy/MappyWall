package dev.mappywall.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class InventoryMapIndex {
    public BindingRepairResult repairManualOpenings(MapWallSave save, List<ObservedMap> observedMaps, Instant repairedAt) {
        Map<String, RouteStep> unboundByRegion = new HashMap<>();
        Map<Integer, String> boundRegionByMapId = new HashMap<>();
        Set<String> boundRegions = new HashSet<>();
        Set<Integer> boundMapIds = new HashSet<>();

        for (MapBinding binding : save.bindings()) {
            boundRegions.add(binding.regionSignature());
            boundMapIds.add(binding.mapId());
            boundRegionByMapId.put(binding.mapId(), binding.regionSignature());
        }

        for (RouteStep step : save.route()) {
            String signature = step.region().signature();
            if (!boundRegions.contains(signature)) {
                unboundByRegion.put(signature, step);
            }
        }

        List<MapBinding> repaired = new ArrayList<>(save.bindings());
        List<String> warnings = new ArrayList<>();
        Map<String, List<ObservedMap>> candidatesByRegion = new HashMap<>();

        for (ObservedMap observed : observedMaps) {
            String signature = observed.regionSignature();
            String alreadyBoundRegion = boundRegionByMapId.get(observed.mapId());
            if (alreadyBoundRegion != null) {
                if (!alreadyBoundRegion.equals(signature)) {
                    warnings.add("Map " + observed.mapId()
                            + " was bound to " + alreadyBoundRegion
                            + " but now reports " + signature);
                }
                continue;
            }

            if (boundRegions.contains(signature)) {
                boundMapIds.add(observed.mapId());
                continue;
            }

            if (!unboundByRegion.containsKey(signature)) {
                continue;
            }

            candidatesByRegion.computeIfAbsent(signature, ignored -> new ArrayList<>()).add(observed);
        }

        for (Map.Entry<String, List<ObservedMap>> entry : candidatesByRegion.entrySet()) {
            String signature = entry.getKey();
            List<ObservedMap> candidates = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(ObservedMap::mapId))
                    .toList();
            if (candidates.size() > 1) {
                warnings.add("Multiple maps " + candidates.stream().map(map -> Integer.toString(map.mapId())).toList()
                        + " match unbound region " + signature);
                continue;
            }

            RouteStep match = unboundByRegion.remove(signature);
            if (match == null) {
                continue;
            }
            ObservedMap observed = candidates.getFirst();
            repaired.add(new MapBinding(
                    match.wallPos(),
                    signature,
                    observed.mapId(),
                    repairedAt,
                    BindingVerification.MANUAL_REPAIR
            ));
            boundRegions.add(signature);
            boundMapIds.add(observed.mapId());
            boundRegionByMapId.put(observed.mapId(), signature);
        }

        return new BindingRepairResult(repaired, warnings);
    }
}
