package dev.mappywall.client;

import dev.mappywall.core.InitialPathPrefixValidator;
import dev.mappywall.core.InitialPathPrefixValidator.InitialPathStep;
import dev.mappywall.core.InitialPathPrefixValidator.InitialStepKind;
import dev.mappywall.core.InitialPathPrefixValidator.PrefixDecision;
import dev.mappywall.core.InitialPathPrefixValidator.PrefixStatus;
import dev.mappywall.core.PathSegmentCoordinator.Anchor;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/** Converts a structural prefix decision into a non-empty, actual-feet-based plan. */
final class InitialPathPlanPreparer {
    private static final int MAX_PREFIX_PROBE_STEPS = 3;

    private final InitialPathPrefixValidator validator = new InitialPathPrefixValidator();

    Optional<PreparedInitialPath> prepare(
            BlockPos actualFeet,
            LocalPathPlanner.PathPlan plan
    ) {
        Objects.requireNonNull(actualFeet, "actualFeet");
        Objects.requireNonNull(plan, "plan");

        List<InitialPathStep> structuralSteps = plan.steps().stream()
                .map(step -> new InitialPathStep(
                        anchor(step.pos()),
                        InitialStepKind.valueOf(step.action().name())
                ))
                .toList();
        PrefixDecision decision = validator.validate(
                anchor(actualFeet),
                anchor(plan.plannedStart()),
                structuralSteps,
                MAX_PREFIX_PROBE_STEPS
        );
        if (decision.status() == PrefixStatus.REJECT) {
            return Optional.empty();
        }

        int trimCount = decision.trimCount();
        if (trimCount >= plan.steps().size()) {
            return Optional.empty();
        }
        LocalPathPlanner.PathPlan prepared = new LocalPathPlanner.PathPlan(
                actualFeet,
                plan.steps().subList(trimCount, plan.steps().size()),
                plan.plannedEnd(),
                plan.outcome(),
                plan.expandedNodes()
        );
        return Optional.of(new PreparedInitialPath(prepared, trimCount));
    }

    private static Anchor anchor(BlockPos pos) {
        return new Anchor(pos.getX(), pos.getY(), pos.getZ());
    }

    record PreparedInitialPath(LocalPathPlanner.PathPlan plan, int trimCount) {
        PreparedInitialPath {
            Objects.requireNonNull(plan, "plan");
            if (trimCount < 0 || plan.steps().isEmpty()) {
                throw new IllegalArgumentException("prepared plan must be non-empty");
            }
        }
    }
}
