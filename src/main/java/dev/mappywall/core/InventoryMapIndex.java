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
                        // Newly opened maps can briefly report a stale/default MapState on the client.
                        // Trust the target-capture binding until the map state agrees with its planned region.
                        continue;
                    } else {
                        warnings.add("地图 " + observed.mapId()
                                + " 已绑定到 " + alreadyBoundRegion
                                + "，但现在读取为 " + signature);
                    }
                }
                continue;
            }

            if (matchesAnyRouteRegion(routeByRegion, boundRegions, observed)) {
                boundMapIds.add(observed.mapId());
                continue;
            }

            String matchingSignature = matchingUnboundRegion(unboundByRegion, observed);
            if (matchingSignature == null) {
                continue;
            }

            candidatesByRegion.computeIfAbsent(matchingSignature, ignored -> new ArrayList<>()).add(observed);
        }

        for (Map.Entry<String, List<ObservedMap>> entry : candidatesByRegion.entrySet()) {
            String signature = entry.getKey();
            List<ObservedMap> candidates = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(ObservedMap::mapId))
                    .toList();
            if (candidates.size() > 1) {
                warnings.add("多个地图 " + candidates.stream().map(map -> Integer.toString(map.mapId())).toList()
                        + " 同时匹配未绑定区域 " + signature);
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

        for (int index = 0; index < repaired.size(); index++) {
            MapBinding binding = repaired.get(index);
            if (binding.verifiedBy() != BindingVerification.TARGET_CAPTURE) {
                continue;
            }
            for (ObservedMap observed : observedMaps) {
                if (observed.mapId() == binding.mapId() && observed.regionSignature().equals(binding.regionSignature())) {
                    repaired.set(index, new MapBinding(
                            binding.wallPos(),
                            binding.regionSignature(),
                            binding.mapId(),
                            binding.openedAt(),
                            BindingVerification.MAP_STATE
                    ));
                    break;
                }
            }
        }

        return new BindingRepairResult(repaired, warnings);
    }

    private boolean matchesAnyRouteRegion(
            Map<String, RouteStep> routeByRegion,
            Set<String> candidateSignatures,
            ObservedMap observed
    ) {
        for (String signature : candidateSignatures) {
            RouteStep step = routeByRegion.get(signature);
            if (step != null && matchesObservedMap(step, observed)) {
                return true;
            }
        }
        return false;
    }

    private String matchingUnboundRegion(Map<String, RouteStep> unboundByRegion, ObservedMap observed) {
        for (Map.Entry<String, RouteStep> entry : unboundByRegion.entrySet()) {
            if (matchesObservedMap(entry.getValue(), observed)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private boolean matchesObservedMap(RouteStep step, ObservedMap observed) {
        if (step.region().signature().equals(observed.regionSignature())) {
            return true;
        }
        return observed.scale() == 0
                && step.region().scale() > 0
                && step.region().dimension().equals(observed.dimension())
                && step.region().bounds().contains(observed.centerX(), observed.centerZ());
    }
}
