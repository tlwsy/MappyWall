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
        Map<String, RouteStep> routeByRegion = new HashMap<>();

        for (RouteStep step : save.route()) {
            String signature = step.region().signature();
            routeByRegion.put(signature, step);
        }

        List<MapBinding> repaired = new ArrayList<>(save.bindings());
        NormalizedObservations normalizedObservations = normalizeObservedMaps(observedMaps);
        List<String> warnings = new ArrayList<>(normalizedObservations.warnings());
        Set<Integer> trueConflictMapIds = new HashSet<>(normalizedObservations.conflictingMapIds());

        for (ObservedMap observed : normalizedObservations.maps().stream()
                .sorted(Comparator.comparingInt(ObservedMap::mapId))
                .toList()) {
            String matchingSignature = matchingRouteRegion(routeByRegion, observed);
            RouteStep observedStep = matchingSignature == null ? null : routeByRegion.get(matchingSignature);
            MapBinding existing = findBindingByMapId(repaired, observed.mapId());
            if (existing != null) {
                RouteStep boundStep = routeByRegion.get(existing.regionSignature());
                boolean matchesBoundRegion = boundStep != null && matchesObservedMap(boundStep, observed);
                if (matchesBoundRegion) {
                    if (existing.verifiedBy() == BindingVerification.TARGET_CAPTURE
                            || existing.verifiedBy() == BindingVerification.PENDING_VERIFICATION) {
                        replaceVerification(
                                repaired,
                                existing,
                                exactTargetScale(boundStep, observed)
                                        ? BindingVerification.TARGET_SCALE
                                        : BindingVerification.MAP_STATE
                        );
                    }
                    continue;
                }

                if (existing.verifiedBy() == BindingVerification.TARGET_CAPTURE
                        && isWithinTargetCaptureGrace(existing, repairedAt)) {
                    // A newly allocated id can briefly expose stale/default state.
                    continue;
                }

                // One positive MapState is authoritative over a stale persisted
                // association, regardless of how the old association was verified.
                // Reassign it when it belongs to this route, otherwise simply release
                // it. If another job still claims a different region, the cross-job
                // index will surface the actual rare conflict and list those jobs.
                removeBinding(repaired, existing.mapId());
                if (observedStep != null) {
                    repaired.add(observedBinding(observedStep, observed, repairedAt));
                }
                continue;
            }

            if (observedStep != null) {
                // Every distinct id for the same region is useful: the player may hang
                // any one of them.  Persist all aliases instead of choosing one and
                // treating the remainder as ambiguous.
                repaired.add(observedBinding(observedStep, observed, repairedAt));
            }
        }

        for (MapBinding binding : repaired) {
            if (binding.verifiedBy() == BindingVerification.PENDING_VERIFICATION) {
                trueConflictMapIds.add(binding.mapId());
                warnings.add("地图 " + binding.mapId()
                        + " 的绑定仍等待正确 MapState 验证；将地图移出背包不会自动解除冲突");
            }
        }

        return new BindingRepairResult(repaired, warnings, trueConflictMapIds);
    }

    private MapBinding observedBinding(RouteStep step, ObservedMap observed, Instant repairedAt) {
        return new MapBinding(
                step.wallPos(),
                step.region().signature(),
                observed.mapId(),
                repairedAt,
                exactTargetScale(step, observed)
                        ? BindingVerification.TARGET_SCALE
                        : BindingVerification.MANUAL_REPAIR
        );
    }

    private MapBinding findBindingByMapId(List<MapBinding> bindings, int mapId) {
        for (MapBinding binding : bindings) {
            if (binding.mapId() == mapId) {
                return binding;
            }
        }
        return null;
    }

    private void removeBinding(List<MapBinding> bindings, int mapId) {
        bindings.removeIf(binding -> binding.mapId() == mapId);
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
        return new NormalizedObservations(normalized, warnings, conflictingMapIds);
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

    private record NormalizedObservations(
            List<ObservedMap> maps,
            List<String> warnings,
            Set<Integer> conflictingMapIds
    ) {
        private NormalizedObservations {
            maps = List.copyOf(maps);
            warnings = List.copyOf(warnings);
            conflictingMapIds = Set.copyOf(conflictingMapIds);
        }
    }
}
