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
        Map<String, RouteStep> routeByRegion = new HashMap<>();
        Map<Integer, MapBinding> bindingByMapId = new HashMap<>();
        Map<Integer, String> boundRegionByMapId = new HashMap<>();
        Set<String> boundRegions = new HashSet<>();
        Set<Integer> boundMapIds = new HashSet<>();

        for (MapBinding binding : save.bindings()) {
            boundRegions.add(binding.regionSignature());
            boundMapIds.add(binding.mapId());
            bindingByMapId.put(binding.mapId(), binding);
            boundRegionByMapId.put(binding.mapId(), binding.regionSignature());
        }

        for (RouteStep step : save.route()) {
            String signature = step.region().signature();
            routeByRegion.put(signature, step);
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
                    MapBinding binding = bindingByMapId.get(observed.mapId());
                    if (binding != null && binding.verifiedBy() == BindingVerification.TARGET_CAPTURE) {
                        repairTargetCaptureMismatch(
                                repaired,
                                binding,
                                observed,
                                routeByRegion,
                                boundRegions,
                                boundRegionByMapId,
                                unboundByRegion,
                                warnings
                        );
                    } else {
                        warnings.add("Map " + observed.mapId()
                                + " was bound to " + alreadyBoundRegion
                                + " but now reports " + signature);
                    }
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

    private void repairTargetCaptureMismatch(
            List<MapBinding> repaired,
            MapBinding binding,
            ObservedMap observed,
            Map<String, RouteStep> routeByRegion,
            Set<String> boundRegions,
            Map<Integer, String> boundRegionByMapId,
            Map<String, RouteStep> unboundByRegion,
            List<String> warnings
    ) {
        String observedSignature = observed.regionSignature();
        RouteStep correctedStep = routeByRegion.get(observedSignature);
        if (correctedStep == null) {
            warnings.add("Map " + observed.mapId()
                    + " was opened for " + binding.regionSignature()
                    + " but reports an area outside this wall: " + observedSignature);
            return;
        }

        if (boundRegions.contains(observedSignature)) {
            warnings.add("Map " + observed.mapId()
                    + " reports already-bound region " + observedSignature
                    + " instead of " + binding.regionSignature());
            return;
        }

        int bindingIndex = repaired.indexOf(binding);
        if (bindingIndex < 0) {
            warnings.add("Map " + observed.mapId() + " binding could not be repaired");
            return;
        }

        repaired.set(bindingIndex, new MapBinding(
                correctedStep.wallPos(),
                observedSignature,
                observed.mapId(),
                binding.openedAt(),
                BindingVerification.MAP_STATE
        ));
        boundRegions.remove(binding.regionSignature());
        boundRegions.add(observedSignature);
        boundRegionByMapId.put(observed.mapId(), observedSignature);
        unboundByRegion.put(binding.regionSignature(), routeByRegion.get(binding.regionSignature()));
        unboundByRegion.remove(observedSignature);
    }
}
