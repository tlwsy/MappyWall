# Fast Planner Rollback with Safety Guards: Engineering Design and Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `superpowers:subagent-driven-development` or `superpowers:executing-plans`.
> Follow RED → GREEN → REFACTOR for every behavior change and review each task
> before continuing.

**Branch:** `26.2/navigation-fast-rollback`

**Goal:** Restore the fast, progress-oriented local A* behavior from the last
known responsive planner while retaining the current hard safety checks for
hazards, caves, live collisions, and incomplete multi-block drops.

**Architecture:** Keep the current immutable snapshot, explicit `PathOutcome`,
segment coordinator, and live action validator. Simplify only endpoint
selection and failure handling toward the `30b1081` behavior: choose the
farthest verified safe progress, never exhaust a second modification search
after `NODE_LIMIT`, and remove the successful-segment cooldown from the normal
continuation path.

**Tech stack:** Java 25, Fabric Loom 1.17.13, Minecraft 26.2 official mappings,
JUnit 5.14, client-only Fabric APIs.

## 1. Problem statement and evidence

The regression is not caused by a smaller configured search range. Both the
responsive baseline and the current implementation use:

```text
MAX_NODES = 4500
MAX_HORIZONTAL_RANGE = 28
MAX_VERTICAL_RANGE = 8
```

The behavior changed in four places:

1. `betterPartial` compares accumulated cost before remaining target distance.
   The current regression test therefore requires a one-step endpoint even
   though a connected three-step endpoint exists.
2. `bestDropFrontier` is returned only when the search reaches
   `maxNodes - 1`. The cliff test measured `10.704s` inside one planner call.
3. `NODE_LIMIT` returns an empty plan. Aggressive mode then runs a second full
   search with breaking and placement enabled, allowing up to roughly 9,000
   popped nodes plus a much larger state key space.
4. A valid five- or six-step segment can finish while a 50-tick successful-plan
   cooldown remains. Only then does a 3,249-column capture begin at 192 columns
   per tick, imposing another 17-tick minimum before A* submission.

The resulting empty-path branch sends neutral input and later restarts direct
movement, producing the visible stop/start twitch.

## 2. Objectives

### 2.1 Functional objectives

- On equal safety risk, select the endpoint with the greatest verified target
  progress; use accumulated cost only as a tie-breaker.
- Preserve explicit `REACHED_TARGET`, `SAFE_FRONTIER`, `UNLOADED_FRONTIER`,
  `NODE_LIMIT`, and `NO_PATH` outcomes.
- Preserve danger-block, lava, support, diagonal, headroom, cave, water, and
  drop-debt validation.
- Never execute an incomplete path with unrecovered multi-block drop debt.
- Never start a modification-capable search unless the non-modifying search
  actually proves `NO_PATH`.
- Start the next capture immediately when an executable segment genuinely
  exhausts without a ready buffer.
- Keep target-generation and exact-seam rejection unchanged.

### 2.2 Performance objectives

- The current cliff regression must complete below two seconds on the same
  machine where it measured `10.704s`; deterministic node-count assertions are
  authoritative if wall-clock timing varies.
- A connected flat or isolated safe route must return its farthest verified
  endpoint rather than one step.
- A normal short-segment exhaustion must add zero successful-plan cooldown
  ticks before capture begins.
- The 57×57 capture must complete in at most seven ticks at 512 columns/tick.
- A deterministic 80-block scenario must cross local seams without an
  input-neutral logical tick when the continuation is ready.

### 2.3 Non-goals

- This option does not introduce chunk-scale or global routing.
- It does not change the 28-block local range.
- It does not add failed-edge memory or a coarse route cache.
- It does not change sprint, walking, swimming, boat, Elytra, jump, breaking,
  placement, interaction, or packet speeds.
- It does not remove the current safe stop when no verified path exists.

## 3. Constraints

- Remain client-only and compatible with vanilla servers.
- Read Minecraft world state only on the client thread.
- Submit only immutable snapshots and request values to the worker.
- Invalidate all old-target active, capture, future, and buffered work before
  another waypoint executes.
- Do not solve performance by allowing a three-block partial drop, entering an
  unproved cave, moving through an unloaded cell, or blindly continuing after
  path exhaustion.
- Preserve movement and packet methods byte-for-byte.

## 4. Design

### 4.1 Progress-oriented safe frontier

The endpoint comparator is lexicographic:

```text
surface / cave risk
unrecovered drop debt (must be absent)
remaining target heuristic
accumulated travel cost
deterministic coordinate tie-break
```

This is the relevant part of the responsive baseline: target progress is not
allowed to lose merely because the first step is cheaper. Current surface risk
still ranks before progress, so a cave shortcut cannot beat a surface detour.

Only these nodes may become executable partial endpoints:

- loaded and standable or swimmable;
- safe under the current hazard rules;
- surface-like when planning began on the surface;
- closer to surface recovery when planning began underground;
- free of unrecovered multi-block drop debt;
- not the start node.

### 4.2 Budget behavior

When the node budget is reached:

- return `SAFE_FRONTIER` with the best verified safe candidate when one exists;
- otherwise return empty `NODE_LIMIT`;
- never call the modification search after `NODE_LIMIT`.

The special `bestDropFrontier` wait-to-`maxNodes - 1` path is removed. A cliff
edge can still be the best safe endpoint, but it is selected by the ordinary
frontier comparator rather than by deliberately exhausting the full search.
After the first safe cliff candidate, the rollback branch permits exactly
`ROLLBACK_FRONTIER_PROOF_NODES = 128` additional non-stale expansions for a
debt-free route with better target heuristic, then returns the best safe
candidate. This deliberately favors response time over long local detours.

### 4.3 Modification fallback

The planner entry point becomes:

```java
PathPlan safePlan = search(snapshot, routeStep, nonModifying);
if (safePlan.outcome() != PathOutcome.NO_PATH
        || (!config.blockBreakingEnabled() && !config.blockPlacingEnabled())) {
    return safePlan;
}
return search(snapshot, routeStep, config);
```

Breaking and placement therefore remain true last resorts. A timeout or node
budget is not evidence that no safe route exists.

### 4.4 Minimal continuation repair

The current coordinator remains. Controller timing changes are intentionally
small:

- `replanCooldown` is failure backoff only;
- installing a valid segment sets the failure backoff to zero;
- an exhausted segment with no capture/future/buffer starts capture immediately;
- lookahead begins with up to 28 steps remaining;
- snapshot capture advances 512 columns per tick;
- initial plan acceptance validates the first live transition from the actual
  player position before installing the segment;
- when no waypoint exists in aggressive mode, the safety stop clears residual
  horizontal velocity while preserving vertical velocity.

This does not alter cruise speeds. It prevents the old direct velocity from
carrying the player away from an incremental capture's start.

## 5. Component and data flow

```text
client tick
  -> capture at most 512 columns
  -> submit immutable snapshot
  -> fast non-modifying local A*
       -> target / safe frontier / unloaded frontier / node limit / no path
       -> modification A* only for true NO_PATH
  -> validate request generation and live first transition
  -> install active or exact-seam buffered segment
  -> execute existing movement/action method
```

`LocalPathPlanner` decides safe progress. `NavigationSnapshotCapture` copies
terrain. `PathSegmentCoordinator` owns exact active/pending/buffered lifecycle.
`MovementController` validates live state and executes existing actions.

## 6. Detailed TDD implementation plan

### Task 1: Reverse the short-frontier regression

**Files:**

- Modify: `src/test/java/dev/mappywall/client/LocalPathPlannerTest.java`
- Modify: `src/client/java/dev/mappywall/client/LocalPathPlanner.java`

- [ ] Replace
  `partialFrontierPrefersLowerCostBeforeTargetHeuristicAtEqualSurfaceRisk()`
  with `partialFrontierMaximizesProgressAtEqualSafety()`.
- [ ] Use the existing isolated surface and assert:

```java
assertEquals(PathOutcome.SAFE_FRONTIER, plan.outcome());
assertEquals(new BlockPos(3, 65, 1), plan.plannedEnd());
assertTrue(plan.steps().size() > 1);
```

- [ ] Run:

```powershell
.\gradlew.bat test --tests "dev.mappywall.client.LocalPathPlannerTest.partialFrontierMaximizesProgressAtEqualSafety" --no-daemon
```

  Expected RED: current planner returns `(1,65,0)`.
- [ ] Change `betterPartial` to compare safety risk, then heuristic, then cost,
  with a deterministic position tie-break.
- [ ] Re-run the focused test; expected GREEN.
- [ ] Run all `LocalPathPlannerTest` methods to ensure cave and drop behavior
  remains green.

### Task 2: Return safe progress at budget and stop double searching

**Files:**

- Modify: `src/test/java/dev/mappywall/client/LocalPathPlannerTest.java`
- Modify: `src/client/java/dev/mappywall/client/LocalPathPlanner.java`

- [ ] Add `nodeBudgetReturnsFarthestSafeProgressInsteadOfEmpty()` using a small
  planner budget and a connected safe prefix; assert non-empty
  `SAFE_FRONTIER` at the farthest popped safe candidate.
- [ ] Add `nodeLimitDoesNotEnableModificationFallback()` using an aggressive
  config and a terrain where the safe search reaches its node budget before an
  allowed obstacle; assert the outcome remains `NODE_LIMIT` or the existing
  non-modifying safe prefix and contains no BREAK/PLACE action.
- [ ] Run both tests and verify RED.
- [ ] Return `bestSafe` before empty `NODE_LIMIT` when it is present.
- [ ] Invoke modification search only after `NO_PATH`.
- [ ] Remove `bestDropFrontier` and
  `onlyTargetImprovingContinuationAddsDropDebt` if no remaining test or caller
  needs them.
- [ ] Re-run the focused and complete planner suites; expected GREEN.

### Task 3: Lock the cliff regression to a bounded search

**Files:**

- Modify: `src/client/java/dev/mappywall/client/LocalPathPlanner.java`
- Modify: `src/test/java/dev/mappywall/client/LocalPathPlannerTest.java`

- [ ] Extend `PathPlan` with package-visible diagnostic `expandedNodes`, or add a
  package-visible result wrapper used only by tests without exposing mutable
  planner state.
- [ ] Replace the misleading cliff test with
  `cliffFrontierReturnsBeforeFullNodeBudget()` and assert:

```java
assertEquals(PathOutcome.SAFE_FRONTIER, plan.outcome());
assertFalse(containsMultiBlockDrop(plan));
assertTrue(plan.expandedNodes() < 1_000);
```

- [ ] Add a non-preemptive `assertTimeout(Duration.ofSeconds(2), ...)` as a
  coarse regression alarm; node count remains the deterministic requirement.
- [ ] Run the test and verify RED against the current 4,499-node behavior.
- [ ] Add stale-priority-queue entry skipping and stop once no queued candidate
  can beat the chosen safe frontier within the 128-node rollback proof budget.
- [ ] Re-run the cliff, debt-free detour, cave, and full planner suites.

### Task 4: Remove normal continuation cooldown and shorten capture latency

**Files:**

- Modify: `src/client/java/dev/mappywall/client/MovementController.java`
- Modify: `src/test/java/dev/mappywall/core/NavigationContinuityScenarioTest.java`
- Modify: `src/test/java/dev/mappywall/client/NavigationSnapshotCaptureTest.java`

- [ ] Add `shortSegmentExhaustionDoesNotWaitForSuccessCooldown()` to a pure
  pipeline driver: install a six-step segment, consume it, and assert an initial
  continuation request may begin on the next logical tick.
- [ ] Add `captureBudgetCompletesFullWindowInSevenTicks()` and drain a full
  3,249-column fixture with budgets of 512; assert seven calls.
- [ ] Run both tests and verify RED against the 50-tick/192-column policy.
- [ ] Change `SNAPSHOT_COLUMNS_PER_TICK` to `512` and
  `LOOKAHEAD_REMAINING_STEPS` to `28`.
- [ ] Stop assigning a 50-tick cooldown after a valid plan begins or installs;
  retain a bounded backoff only for exceptions, `NO_PATH`, or repeated rejected
  starts.
- [ ] Re-run focused continuity/capture tests and all core/client tests.

### Task 5: Reject stale initial prefixes before they twitch

**Files:**

- Modify: `src/client/java/dev/mappywall/client/MovementController.java`
- Create: `src/main/java/dev/mappywall/core/InitialPathPrefixValidator.java`
- Create: `src/test/java/dev/mappywall/core/InitialPathPrefixValidatorTest.java`

- [ ] Write tests for exact start, player already on the first movement step,
  first step two columns away, unsafe diagonal, and modification action from a
  non-adjacent position.
- [ ] Define nested records/enums in the validator:

```java
public record Step(PathSegmentCoordinator.Anchor end, Kind kind) {}
public enum Kind { WALK, JUMP, DROP, SWIM, BREAK, PLACE }
public record Decision(Status status, int trimCount) {}
public enum Status { ACCEPT, TRIM, REJECT }
```
- [ ] Run focused tests; expected RED because the validator is absent.
- [ ] Implement a pure structural prefix result (`ACCEPT`, `TRIM_PREFIX`,
  `REJECT`) and combine it with existing live support/hazard checks in the
  controller.
- [ ] On `REJECT`, discard without installing and immediately recapture from the
  actual stopped player position.
- [ ] In the no-waypoint safety branch only, clear aggressive horizontal delta
  while preserving `deltaY`; do not alter any movement speed constant.
- [ ] Re-run focused tests, all controller-adjacent tests, and the full suite.

### Task 6: Documentation and release verification

**Files:**

- Modify: `docs/design.md`
- Modify: `gradle.properties`

- [ ] Document that option 1 restores progress-oriented local A* while keeping
  hard safety filters.
- [ ] Bump the patch version once implementation behavior is accepted.
- [ ] Run:

```powershell
.\gradlew.bat clean build --no-daemon
git diff --check
```

- [ ] Parse all JSON resources and inspect test XML for zero failures/errors.
- [ ] Inspect the release jar for `MovementController`, `LocalPathPlanner`,
  `NavigationSnapshotCapture`, `InitialPathPrefixValidator`, and
  `PathSegmentCoordinator`.
- [ ] Independently review cave/drop safety, node-limit semantics, short-segment
  continuity, stale initial prefixes, and unchanged movement/packet code.

## 7. Acceptance matrix

| Scenario | Required result |
| --- | --- |
| Equal-risk connected prefix | Farthest verified progress, never cheapest one-step endpoint |
| Full-width three-block cliff | Fast safe stop without committing DROP debt |
| Cliff with loaded detour | Detour selected before nearby cliff frontier |
| Cave shortcut versus surface path | Surface path remains preferred |
| Planner node limit with safe prefix | Executable safe prefix; no second modifying search |
| Planner node limit without safe prefix | Empty `NODE_LIMIT`; bounded retry |
| Six-step segment | No 50-tick successful-plan delay |
| Initial capture while player coasts | Invalid prefix rejected before execution; recapture from stopped feet |
| Target change | Old active/pending/buffered path never executes |
| Aggressive free-look | Existing server-facing direction behavior unchanged |

## 8. Risks and trade-offs

- Progress-first partial selection may choose a longer safe route with greater
  travel cost. Safety risk still precedes progress, and A* cost still influences
  complete route selection.
- Removing exhaustive cliff proof can miss a very long local detour. This is a
  deliberate emergency rollback trade-off; option 3 provides global corridors.
- A 512-column capture budget increases per-tick copying compared with 192.
  It remains far below the old one-tick full capture and must be profiled in a
  manual client run.
- Clearing residual horizontal velocity makes a genuine no-path stop sharper.
  It applies only when there is no verified waypoint, not during normal travel.

## 9. Migration and rollback

No save or navigation-config schema changes are required. The branch can be
tested against existing projects and vanilla servers.

Rollback options, in order:

1. revert only the controller timing task if 512-column capture causes frame
   spikes;
2. restore 192 columns/tick while retaining no-success-cooldown semantics;
3. revert the whole branch to the common documentation baseline and select the
   option-2 branch.

Because this plan changes no project save format and no server protocol,
rollback requires no data migration.

## 10. Decision summary

This is the smallest route back to responsive behavior. It is appropriate when
fast recovery is more important than improving the planner architecture. Its
main limitation is that it keeps the 28-block local worldview, so route quality
around terrain features wider than that window remains bounded.
