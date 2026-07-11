package dev.mappywall.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class InventoryMapIndex {
    public static final Duration TARGET_CAPTURE_GRACE = Duration.ofSeconds(10);

    public BindingRepairResult repairManualOpenings(MapWallSave save, List<ObservedMap> observedMaps, Instant repairedAt) {
        Objects.requireNonNull(save, "save");
        Objects.requireNonNull(observedMaps, "observedMaps");
        Objects.requireNonNull(repairedAt, "repairedAt");
        Map<String, RouteStep> unboundByRegion = new HashMap<>();
        Map<String, RouteStep> routeByRegion = new HashMap<>();
        Map<Integer, MapBinding> bindingByMapId = new HashMap<>();
        Map<Integer, String> boundRegionByMapId = new HashMap<>();
        Set<String> boundRegions = new HashSet<>();

        for (MapBinding binding : save.bindings()) {
            boundRegions.add(binding.regionSignature());
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
        NormalizedObservations normalizedObservations = normalizeObservedMaps(observedMaps);
        List<String> warnings = new ArrayList<>(normalizedObservations.warnings());
        Map<String, List<ObservedMap>> candidatesByRegion = new java.util.TreeMap<>();

        for (ObservedMap observed : normalizedObservations.maps()) {
            String signature = observed.regionSignature();
            String alreadyBoundRegion = boundRegionByMapId.get(observed.mapId());
            if (alreadyBoundRegion != null) {
                RouteStep boundStep = routeByRegion.get(alreadyBoundRegion);
                boolean matchesBoundRegion = boundStep != null && matchesObservedMap(boundStep, observed);
                if (!matchesBoundRegion) {
                    MapBinding binding = bindingByMapId.get(observed.mapId());
                    if (binding != null
                            && binding.verifiedBy() == BindingVerification.TARGET_CAPTURE
                            && isWithinTargetCaptureGrace(binding, repairedAt)) {
                        // Newly opened maps can briefly report a stale/default MapState on the client.
                        // Trust target capture only for a short, persisted grace window.
                        continue;
                    } else {
                        warnings.add("地图 " + observed.mapId()
                                + " 已绑定到 " + alreadyBoundRegion
                                + "，但现在读取为 " + signature);
                        if (binding != null) {
                            replaceVerification(repaired, binding, BindingVerification.PENDING_VERIFICATION);
                        }
                    }
                }
                continue;
            }

            String matchingSignature = matchingRouteRegion(routeByRegion, observed);
            if (matchingSignature == null || boundRegions.contains(matchingSignature)) {
                continue;
            }
            if (!unboundByRegion.containsKey(matchingSignature)) {
                continue;
            }

            candidatesByRegion.computeIfAbsent(matchingSignature, ignored -> new ArrayList<>()).add(observed);
        }

        for (Map.Entry<String, List<ObservedMap>> entry : candidatesByRegion.entrySet()) {
            String signature = entry.getKey();
            RouteStep match = unboundByRegion.remove(signature);
            if (match == null) {
                continue;
            }
            List<ObservedMap> candidates = entry.getValue().stream()
                    // Different map ids can legitimately describe the same route region.
                    // Prefer the map requiring the fewest remaining zoom operations, then
                    // use the id only as a stable tie-breaker.
                    .sorted(Comparator.comparingInt(ObservedMap::scale).reversed()
                            .thenComparingInt(ObservedMap::mapId))
                    .toList();
            ObservedMap observed = candidates.getFirst();
            repaired.add(new MapBinding(
                    match.wallPos(),
                    signature,
                    observed.mapId(),
                    repairedAt,
                    exactTargetScale(match, observed)
                            ? BindingVerification.TARGET_SCALE
                            : BindingVerification.MANUAL_REPAIR
            ));
            boundRegions.add(signature);
            boundRegionByMapId.put(observed.mapId(), signature);
        }

        for (int index = 0; index < repaired.size(); index++) {
            MapBinding binding = repaired.get(index);
            if (binding.verifiedBy() != BindingVerification.TARGET_CAPTURE
                    && binding.verifiedBy() != BindingVerification.PENDING_VERIFICATION) {
                continue;
            }
            for (ObservedMap observed : normalizedObservations.maps()) {
                RouteStep boundStep = routeByRegion.get(binding.regionSignature());
                if (observed.mapId() == binding.mapId()
                        && boundStep != null
                        && matchesObservedMap(boundStep, observed)) {
                    repaired.set(index, new MapBinding(
                            binding.wallPos(),
                            binding.regionSignature(),
                            binding.mapId(),
                            binding.openedAt(),
                            exactTargetScale(boundStep, observed)
                                    ? BindingVerification.TARGET_SCALE
                                    : BindingVerification.MAP_STATE
                    ));
                    break;
                }
            }
        }

        for (MapBinding binding : repaired) {
            if (binding.verifiedBy() == BindingVerification.PENDING_VERIFICATION) {
                warnings.add("地图 " + binding.mapId()
                        + " 的绑定仍等待正确 MapState 验证；将地图移出背包不会自动解除冲突");
            }
        }

        return new BindingRepairResult(repaired, warnings);
    }

    private NormalizedObservations normalizeObservedMaps(List<ObservedMap> observedMaps) {
        Map<Integer, ObservedMap> byMapId = new LinkedHashMap<>();
        Set<Integer> conflictingMapIds = new HashSet<>();
        List<String> warnings = new ArrayList<>();
        for (ObservedMap observed : observedMaps) {
            Objects.requireNonNull(observed, "observed map");
            ObservedMap existing = byMapId.putIfAbsent(observed.mapId(), observed);
            if (existing == null || sameMapState(existing, observed)) {
                continue;
            }
            if (conflictingMapIds.add(observed.mapId())) {
                warnings.add("地图 " + observed.mapId() + " 在库存中报告了互相冲突的区域数据");
            }
        }
        List<ObservedMap> normalized = byMapId.entrySet().stream()
                .filter(entry -> !conflictingMapIds.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
        return new NormalizedObservations(normalized, warnings);
    }

    private boolean sameMapState(ObservedMap left, ObservedMap right) {
        return left.dimension().equals(right.dimension())
                && left.scale() == right.scale()
                && left.centerX() == right.centerX()
                && left.centerZ() == right.centerZ();
    }

    private boolean isWithinTargetCaptureGrace(MapBinding binding, Instant now) {
        if (now.isBefore(binding.openedAt())) {
            return false;
        }
        return Duration.between(binding.openedAt(), now).compareTo(TARGET_CAPTURE_GRACE) <= 0;
    }

    private void replaceVerification(
            List<MapBinding> bindings,
            MapBinding target,
            BindingVerification verification
    ) {
        for (int index = 0; index < bindings.size(); index++) {
            MapBinding candidate = bindings.get(index);
            if (candidate.mapId() == target.mapId()
                    && candidate.regionSignature().equals(target.regionSignature())) {
                bindings.set(index, new MapBinding(
                        candidate.wallPos(),
                        candidate.regionSignature(),
                        candidate.mapId(),
                        candidate.openedAt(),
                        verification
                ));
                return;
            }
        }
    }

    private String matchingRouteRegion(Map<String, RouteStep> routeByRegion, ObservedMap observed) {
        RouteStep exact = routeByRegion.get(observed.regionSignature());
        if (exact != null && matchesObservedMap(exact, observed)) {
            return exact.region().signature();
        }
        if (routeByRegion.isEmpty()) {
            return null;
        }
        RouteStep sample = routeByRegion.values().iterator().next();
        if (!sample.region().dimension().equals(observed.dimension())
                || observed.scale() > sample.region().scale()) {
            return null;
        }
        String projected = MapRegionMath.regionForBlock(
                observed.dimension(),
                sample.region().scale(),
                observed.centerX(),
                observed.centerZ()
        ).signature();
        return routeByRegion.containsKey(projected) ? projected : null;
    }

    private boolean matchesObservedMap(RouteStep step, ObservedMap observed) {
        if (step.region().signature().equals(observed.regionSignature())) {
            return true;
        }
        if (!step.region().dimension().equals(observed.dimension())
                || observed.scale() > step.region().scale()) {
            return false;
        }
        MapRegion projected = MapRegionMath.regionForBlock(
                observed.dimension(),
                step.region().scale(),
                observed.centerX(),
                observed.centerZ()
        );
        return projected.signature().equals(step.region().signature());
    }

    private boolean exactTargetScale(RouteStep step, ObservedMap observed) {
        return step.region().scale() > 0
                && step.region().signature().equals(observed.regionSignature());
    }

    private record NormalizedObservations(List<ObservedMap> maps, List<String> warnings) {
        private NormalizedObservations {
            maps = List.copyOf(maps);
            warnings = List.copyOf(warnings);
        }
    }
}
