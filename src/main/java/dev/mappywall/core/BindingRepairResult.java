package dev.mappywall.core;

import java.util.List;

public record BindingRepairResult(List<MapBinding> bindings, List<String> warnings) {
    public BindingRepairResult {
        bindings = List.copyOf(bindings);
        warnings = List.copyOf(warnings);
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
}

