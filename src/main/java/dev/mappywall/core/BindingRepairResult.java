package dev.mappywall.core;

import java.util.List;
import java.util.Set;

public record BindingRepairResult(
        List<MapBinding> bindings,
        List<String> warnings,
        Set<Integer> trueConflictMapIds
) {
    public BindingRepairResult {
        bindings = List.copyOf(bindings);
        warnings = List.copyOf(warnings);
        trueConflictMapIds = Set.copyOf(trueConflictMapIds);
    }

    /**
     * Compatibility constructor for callers that only produce informational warnings.
     */
    public BindingRepairResult(List<MapBinding> bindings, List<String> warnings) {
        this(bindings, warnings, Set.of());
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    public boolean hasTrueConflicts() {
        return !trueConflictMapIds.isEmpty();
    }
}
