package dev.mappywall.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Detects the rare case where persisted jobs disagree about what one map id represents. */
public final class CrossProjectMapIdIndex {
    public List<Conflict> findConflicts(List<MapWallSave> saves) {
        Map<Integer, Map<String, Set<String>>> projectsByMapAndRegion = new LinkedHashMap<>();
        for (MapWallSave save : List.copyOf(saves)) {
            String projectId = save.project().id();
            for (MapBinding binding : save.finalBindings()) {
                projectsByMapAndRegion
                        .computeIfAbsent(binding.mapId(), ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(binding.regionSignature(), ignored -> new LinkedHashSet<>())
                        .add(projectId);
            }
        }

        List<Conflict> conflicts = new ArrayList<>();
        projectsByMapAndRegion.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(entry -> entry.getValue().size() > 1)
                .forEach(entry -> conflicts.add(new Conflict(entry.getKey(), entry.getValue())));
        return List.copyOf(conflicts);
    }

    public record Conflict(int mapId, Map<String, Set<String>> projectIdsByRegion) {
        public Conflict {
            LinkedHashMap<String, Set<String>> copied = new LinkedHashMap<>();
            projectIdsByRegion.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> copied.put(entry.getKey(), Set.copyOf(entry.getValue())));
            projectIdsByRegion = Map.copyOf(copied);
        }

        public Set<String> involvedProjectIds() {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            projectIdsByRegion.values().stream()
                    .flatMap(Set::stream)
                    .sorted(Comparator.naturalOrder())
                    .forEach(result::add);
            return Set.copyOf(result);
        }
    }
}
