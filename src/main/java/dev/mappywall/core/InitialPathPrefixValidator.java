package dev.mappywall.core;

import dev.mappywall.core.PathSegmentCoordinator.Anchor;
import java.util.List;
import java.util.Objects;

/**
 * Validates whether an asynchronously planned prefix still joins the player's
 * actual feet, without reading Minecraft world state.
 */
public final class InitialPathPrefixValidator {
    public PrefixDecision validate(
            Anchor actualFeet,
            Anchor plannedStart,
            List<InitialPathStep> steps,
            int maxProbeSteps
    ) {
        Objects.requireNonNull(actualFeet, "actualFeet");
        Objects.requireNonNull(plannedStart, "plannedStart");
        List<InitialPathStep> immutableSteps = List.copyOf(
                Objects.requireNonNull(steps, "steps")
        );
        if (maxProbeSteps <= 0) {
            throw new IllegalArgumentException("maxProbeSteps must be positive");
        }
        if (immutableSteps.isEmpty()) {
            return actualFeet.equals(plannedStart) ? PrefixDecision.accept() : PrefixDecision.reject();
        }

        Anchor cursor = plannedStart;
        int probeCount = Math.min(maxProbeSteps, immutableSteps.size());
        for (int index = 0; index < probeCount; index++) {
            InitialPathStep step = immutableSteps.get(index);
            if (step.kind().modifiesWorld()) {
                if (!isLegalTransition(cursor, step)
                        || actualFeet.equals(step.end())) {
                    return PrefixDecision.reject();
                }
                break;
            }
            if (!isLegalTransition(cursor, step)) {
                return PrefixDecision.reject();
            }
            cursor = step.end();
            if (actualFeet.equals(cursor)) {
                int trimCount = index + 1;
                if (trimCount < immutableSteps.size()
                        && !isLegalTransition(actualFeet, immutableSteps.get(trimCount))) {
                    return PrefixDecision.reject();
                }
                return PrefixDecision.trim(trimCount);
            }
        }

        InitialPathStep first = immutableSteps.getFirst();
        if (!actualFeet.equals(plannedStart) && first.kind().modifiesWorld()) {
            return PrefixDecision.reject();
        }
        return isLegalTransition(actualFeet, first)
                ? PrefixDecision.accept()
                : PrefixDecision.reject();
    }

    private boolean isLegalTransition(Anchor from, InitialPathStep step) {
        int deltaX = step.end().x() - from.x();
        int deltaY = step.end().y() - from.y();
        int deltaZ = step.end().z() - from.z();
        if (Math.max(Math.abs(deltaX), Math.abs(deltaZ)) != 1) {
            return false;
        }

        boolean cardinal = Math.abs(deltaX) + Math.abs(deltaZ) == 1;
        return switch (step.kind()) {
            case WALK -> deltaY == 0;
            case JUMP -> cardinal && deltaY >= 0 && deltaY <= 1;
            case DROP -> cardinal && deltaY >= -3 && deltaY <= 0;
            case SWIM -> deltaY >= -3
                    && deltaY <= 1
                    && (deltaY == 0 || cardinal);
            case BREAK, PLACE -> deltaY == 0;
        };
    }

    public enum PrefixStatus {
        ACCEPT,
        TRIM,
        REJECT
    }

    public enum InitialStepKind {
        WALK,
        JUMP,
        DROP,
        SWIM,
        BREAK,
        PLACE;

        public boolean modifiesWorld() {
            return this == BREAK || this == PLACE;
        }
    }

    public record InitialPathStep(Anchor end, InitialStepKind kind) {
        public InitialPathStep {
            Objects.requireNonNull(end, "end");
            Objects.requireNonNull(kind, "kind");
        }
    }

    public record PrefixDecision(PrefixStatus status, int trimCount) {
        public PrefixDecision {
            Objects.requireNonNull(status, "status");
            if (trimCount < 0
                    || (status == PrefixStatus.TRIM && trimCount == 0)
                    || (status != PrefixStatus.TRIM && trimCount != 0)) {
                throw new IllegalArgumentException("trimCount must match prefix status");
            }
        }

        public static PrefixDecision accept() {
            return new PrefixDecision(PrefixStatus.ACCEPT, 0);
        }

        public static PrefixDecision trim(int trimCount) {
            return new PrefixDecision(PrefixStatus.TRIM, trimCount);
        }

        public static PrefixDecision reject() {
            return new PrefixDecision(PrefixStatus.REJECT, 0);
        }
    }
}
