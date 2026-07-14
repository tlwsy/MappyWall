package dev.mappywall.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Owns the deterministic lifecycle of an active path segment and one buffered continuation.
 * Planning and asynchronous execution remain outside this class.
 */
public final class PathSegmentCoordinator<T> {
    private long generation;
    private long nextRequestId;
    private String targetKey;
    private Segment<T> active;
    private int activeStepIndex;
    private LookaheadRequest pending;
    private Segment<T> buffered;

    public record Anchor(int x, int y, int z) {
    }

    public record Segment<T>(
            Anchor start,
            Anchor end,
            List<T> steps,
            boolean terminal,
            boolean prefetchable
    ) {
        public Segment {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            Objects.requireNonNull(steps, "steps");
            steps = List.copyOf(steps);
        }
    }

    public record LookaheadRequest(long id, long generation, String targetKey, Anchor seam) {
        public LookaheadRequest {
            Objects.requireNonNull(targetKey, "targetKey");
            Objects.requireNonNull(seam, "seam");
        }
    }

    public void resetTarget(String targetKey) {
        this.targetKey = Objects.requireNonNull(targetKey, "targetKey");
        invalidateSegments();
        generation++;
    }

    public void installInitial(Segment<T> segment) {
        if (targetKey == null) {
            throw new IllegalStateException("resetTarget must be called before installing a segment");
        }
        active = Objects.requireNonNull(segment, "segment");
        activeStepIndex = 0;
        pending = null;
        buffered = null;
    }

    public Optional<LookaheadRequest> beginLookahead(boolean suffixStable) {
        if (!suffixStable
                || targetKey == null
                || active == null
                || activeStepIndex >= active.steps().size()
                || active.terminal()
                || !active.prefetchable()
                || pending != null
                || buffered != null) {
            return Optional.empty();
        }

        pending = new LookaheadRequest(
                ++nextRequestId,
                generation,
                targetKey,
                active.end()
        );
        return Optional.of(pending);
    }

    public boolean acceptLookahead(LookaheadRequest request, Segment<T> continuation) {
        if (!matchesPending(request)
                || continuation == null
                || active == null
                || buffered != null
                || !request.seam().equals(active.end())
                || !continuation.start().equals(request.seam())) {
            return false;
        }

        buffered = continuation;
        pending = null;
        return true;
    }

    public boolean failLookahead(LookaheadRequest request) {
        if (!matchesPending(request)) {
            return false;
        }
        pending = null;
        return true;
    }

    public Optional<T> currentStep() {
        if (active == null || activeStepIndex >= active.steps().size()) {
            return Optional.empty();
        }
        return Optional.of(active.steps().get(activeStepIndex));
    }

    public Optional<T> currentStepOrPromote() {
        if (active != null && activeStepIndex >= active.steps().size()) {
            promoteBuffered();
        }
        return currentStep();
    }

    public boolean advance() {
        if (active == null || activeStepIndex >= active.steps().size()) {
            return false;
        }
        activeStepIndex++;
        return true;
    }

    public boolean promoteBuffered() {
        if (active == null
                || activeStepIndex < active.steps().size()
                || buffered == null) {
            return false;
        }

        active = buffered;
        activeStepIndex = 0;
        buffered = null;
        return true;
    }

    public int remainingSteps() {
        if (active == null) {
            return 0;
        }
        return active.steps().size() - activeStepIndex;
    }

    public List<T> remainingStepSnapshot() {
        if (active == null || activeStepIndex >= active.steps().size()) {
            return List.of();
        }
        return List.copyOf(active.steps().subList(activeStepIndex, active.steps().size()));
    }

    public void clear() {
        invalidateSegments();
        generation++;
    }

    public boolean hasBuffered() {
        return buffered != null;
    }

    private boolean matchesPending(LookaheadRequest request) {
        return request != null
                && pending == request
                && request.id() == pending.id()
                && request.generation() == pending.generation()
                && request.generation() == generation
                && request.targetKey().equals(pending.targetKey())
                && request.targetKey().equals(targetKey)
                && request.seam().equals(pending.seam());
    }

    private void invalidateSegments() {
        active = null;
        activeStepIndex = 0;
        pending = null;
        buffered = null;
    }
}
