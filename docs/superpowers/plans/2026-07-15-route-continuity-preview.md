# Route Continuity and Preview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent avoidable U-turns between rolling local path segments and display an accepted forward continuation before the active yellow line ends.

**Architecture:** Add an immutable eight-node continuation context to lookahead planning. Run the existing planner with a strict recent-tail/behind-seam guard first and relax it only after exhaustive `NO_PATH`; carry the resulting retreat classification into a coordinator preview policy that is separate from execution promotion.

**Tech Stack:** Java 25, Fabric Loom 1.17.13, Minecraft 26.2 official mappings, JUnit 5.14.

## Global Constraints

- Keep all Minecraft world reads on the client thread; worker input remains immutable.
- Do not change movement speed, packet cadence, search range, or node budget.
- Do not relax on `NODE_LIMIT`; only exhaustive `NO_PATH` proves retreat may be necessary.
- Rendering a buffered continuation must never execute or promote it early.
- Initial planning and existing underground recovery behavior must remain compatible.

---

### Task 1: Coordinator preview policy

**Files:**
- Modify: `src/main/java/dev/mappywall/core/PathSegmentCoordinator.java`
- Modify: `src/test/java/dev/mappywall/core/PathSegmentCoordinatorTest.java`

**Interfaces:**
- Produces: `PathSegmentCoordinator.PreviewPolicy` with `CONTINUOUS` and `AFTER_PROMOTION`.
- Produces: `List<T> previewStepSnapshot()`; existing `remainingStepSnapshot()` remains execution-only.

- [ ] **Step 1: Write failing preview lifecycle tests**

Add tests which install an active segment, accept a buffer, and assert these exact sequences:

```java
assertEquals(List.of(2, 3, 4, 5), coordinator.previewStepSnapshot());
assertEquals(List.of(2, 3), coordinator.remainingStepSnapshot());
```

Cover `CONTINUOUS` early append, `AFTER_PROMOTION` hidden-before/promoted-after behavior, and `resetTarget()` clearing the preview.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
.\gradlew.bat test --tests "dev.mappywall.core.PathSegmentCoordinatorTest" --no-daemon
```

Expected: compilation failure because `PreviewPolicy` and `previewStepSnapshot()` do not exist.

- [ ] **Step 3: Implement the minimal coordinator API**

Append preview metadata without breaking current call sites:

```java
public enum PreviewPolicy {
    CONTINUOUS,
    AFTER_PROMOTION
}

public record Segment<T>(
        Anchor start,
        Anchor end,
        List<T> steps,
        boolean terminal,
        ContinuationPolicy continuationPolicy,
        PreviewPolicy previewPolicy
) {
    public Segment(Anchor start, Anchor end, List<T> steps,
                   boolean terminal, ContinuationPolicy continuationPolicy) {
        this(start, end, steps, terminal, continuationPolicy, PreviewPolicy.CONTINUOUS);
    }
}
```

Implement `previewStepSnapshot()` as a fresh immutable list containing the active remainder and, only for a continuous buffer, all buffered steps. Do not change promotion rules.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the command from Step 2. Expected: all coordinator tests pass.

- [ ] **Step 5: Commit the coordinator unit**

```powershell
git add src/main/java/dev/mappywall/core/PathSegmentCoordinator.java src/test/java/dev/mappywall/core/PathSegmentCoordinatorTest.java
git commit -m "Preview continuous buffered paths"
```

### Task 2: Strict continuation context and necessary-retreat fallback

**Files:**
- Modify: `src/client/java/dev/mappywall/client/LocalPathPlanner.java`
- Modify: `src/test/java/dev/mappywall/client/LocalPathPlannerTest.java`

**Interfaces:**
- Produces: `LocalPathPlanner.ContinuationContext` and overload `plan(snapshot, target, config, context)`.
- Produces: `PathPlan.usedRetreatFallback()`.
- Consumes: existing `PathOutcome.NO_PATH` and `PathOutcome.NODE_LIMIT` semantics.

- [ ] **Step 1: Write failing continuation-context tests**

Add deterministic fixtures for:

```java
ContinuationContext context = ContinuationContext.fromSuffix(suffix, seam);
assertEquals(8, context.recentTrail().size());
assertEquals(1, context.approachDx());
assertEquals(0, context.approachDz());
```

Add behavior tests named:

- `constrainedContinuationDoesNotReenterRecentTrailOrCrossBehindSeam()`;
- `exhaustiveConstrainedNoPathFallsBackAndMarksNecessaryRetreat()`;
- `nodeLimitedConstrainedSearchNeverRelaxesTrailConstraint()`.

The first fixture must contain both a forward detour and a shorter reverse path; assert every returned step has non-negative seam-plane dot product and is absent from the recent tail. The second must be a one-exit corridor whose only legal first edge is the penultimate node. The third uses a deliberately low planner node budget and asserts `NODE_LIMIT` with `usedRetreatFallback() == false`.

- [ ] **Step 2: Run planner tests and verify RED**

```powershell
.\gradlew.bat test --tests "dev.mappywall.client.LocalPathPlannerTest" --no-daemon
```

Expected: compilation failure for the new context and retreat result.

- [ ] **Step 3: Add the immutable context and guard**

Implement the nested record with defensive copying:

```java
public record ContinuationContext(
        int approachDx,
        int approachDz,
        Set<BlockPos> recentTrail
) {
    public ContinuationContext {
        approachDx = Integer.signum(approachDx);
        approachDz = Integer.signum(approachDz);
        recentTrail = Set.copyOf(recentTrail);
    }

    static ContinuationContext none() {
        return new ContinuationContext(0, 0, Set.of());
    }

    boolean constrained() {
        return approachDx != 0 || approachDz != 0;
    }
}
```

`fromSuffix(...)` keeps at most eight positions immediately before the seam and derives the signed horizontal approach from the penultimate position. `allows(searchStart, candidate)` rejects recent-tail positions and candidates whose seam-relative dot product with the approach is negative.

- [ ] **Step 4: Implement guarded-then-relaxed orchestration**

Extract the current safe-first/modification-last body into `planOnce(...)`. The context overload performs:

```java
PathPlan strict = planOnce(snapshot, target, config, context);
if (!context.constrained() || strict.outcome() != PathOutcome.NO_PATH) {
    return strict;
}
PathPlan retreat = planOnce(snapshot, target, config, ContinuationContext.none());
return retreat.withRetreatFallback(true, strict.expandedNodes() + retreat.expandedNodes());
```

Filter every generated neighbor before Pareto registration. Preserve the current rule that modification search runs only after `NO_PATH` inside each `planOnce`. Add `usedRetreatFallback` as the last `PathPlan` component and keep four-/five-argument constructors defaulting it to `false`.

- [ ] **Step 5: Run planner tests and verify GREEN plus performance bound**

Run the Step 2 command twice. Expected: all tests pass; ordinary constrained fixtures perform one search, and the node-limit fixture reports no relaxed expanded-node work.

- [ ] **Step 6: Commit the planner unit**

```powershell
git add src/client/java/dev/mappywall/client/LocalPathPlanner.java src/test/java/dev/mappywall/client/LocalPathPlannerTest.java
git commit -m "Keep rolling paths directionally continuous"
```

### Task 3: Lookahead integration and live yellow preview

**Files:**
- Modify: `src/client/java/dev/mappywall/client/MovementController.java`
- Modify: `src/test/java/dev/mappywall/core/NavigationContinuityScenarioTest.java`

**Interfaces:**
- Consumes: `ContinuationContext.fromSuffix(...)`, `PathPlan.usedRetreatFallback()`, `PreviewPolicy`, and `previewStepSnapshot()`.
- Produces: the existing `MovementResult.path()` now contains accepted continuous buffered steps.

- [ ] **Step 1: Write the failing seam-preview scenario**

Extend the fake rolling scenario so a continuation is accepted two logical ticks before promotion. Assert:

```java
assertEquals(range(8, 42), coordinator.previewStepSnapshot());
assertEquals(8, coordinator.currentStep().orElseThrow());
```

Add the same scenario with `AFTER_PROMOTION` and assert the preview stops at the active seam until promotion.

- [ ] **Step 2: Run continuity tests and verify RED**

```powershell
.\gradlew.bat test --tests "dev.mappywall.core.NavigationContinuityScenarioTest" --no-daemon
```

Expected: the old active-only expectation fails.

- [ ] **Step 3: Thread context through planning requests**

Add non-null `ContinuationContext continuationContext` to `PlanningRequest`. Initial captures use `ContinuationContext.none()`. Before `beginLookahead`, take one active suffix snapshot, create the context from its final nodes and seam, and store it in the request. Submit workers through the new planner overload.

Map planner results in `toSegment(...)`:

```java
PreviewPolicy previewPolicy = plan.usedRetreatFallback()
        ? PreviewPolicy.AFTER_PROMOTION
        : PreviewPolicy.CONTINUOUS;
```

Change only `pathSnapshot()` to map `pathSegments.previewStepSnapshot()`. Keep suffix-stability checks and execution calls on `remainingStepSnapshot()`.

- [ ] **Step 4: Run focused route suites and verify GREEN**

```powershell
.\gradlew.bat test --tests "dev.mappywall.client.LocalPathPlannerTest" --tests "dev.mappywall.core.PathSegmentCoordinatorTest" --tests "dev.mappywall.core.NavigationContinuityScenarioTest" --no-daemon
```

Expected: all tests pass, current execution step is unchanged while the preview includes the forward buffer.

- [ ] **Step 5: Commit integration**

```powershell
git add src/client/java/dev/mappywall/client/MovementController.java src/test/java/dev/mappywall/core/NavigationContinuityScenarioTest.java
git commit -m "Show accepted rolling path continuations"
```

### Task 4: Route regression verification

**Files:**
- Modify: `docs/design.md`

**Interfaces:**
- Documents: strict continuation guard, retreat fallback, and preview-versus-execution semantics.

- [ ] **Step 1: Run route verification**

```powershell
.\gradlew.bat test --tests "dev.mappywall.client.LocalPathPlannerTest" --tests "dev.mappywall.core.PathSegmentCoordinatorTest" --tests "dev.mappywall.core.NavigationContinuityScenarioTest" --rerun-tasks --no-daemon
git diff --check
```

Expected: zero failures and no whitespace errors.

- [ ] **Step 2: Document the implemented state machine**

Add the exact distinction: accepted continuous buffers are previewed but remain non-executable; necessary retreat is possible only after strict `NO_PATH` and stays hidden until promotion.

- [ ] **Step 3: Commit documentation**

```powershell
git add docs/design.md
git commit -m "Document route continuity previews"
```
