# Safe Local Planner and Rolling Pipeline Repair: Engineering Design and Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `superpowers:subagent-driven-development` or `superpowers:executing-plans`.
> Execute each task with a demonstrated RED test, the minimal GREEN change, and
> an independent task review before continuing.

**Branch:** `26.2/navigation-pipeline-repair`

**Goal:** Repair the current safety-aware local planner and asynchronous
lookahead pipeline so ordinary routes produce useful long segments, planning
finishes before segment exhaustion, and the controller does not repeatedly
stop/restart or reinstall an invalid first edge.

**Architecture:** Retain the current explicit path outcomes, surface/cave risk,
drop-debt safety, immutable terrain snapshot, generic segment coordinator, and
live action validation. Correct the planner's frontier semantics and state-space
dominance; separate successful continuation from failure backoff; accelerate
and start lookahead earlier; make unloaded frontiers refreshable when chunks
arrive; and validate initial path prefixes against the actual player position
before installation.

**Tech stack:** Java 25, Fabric Loom 1.17.13, Minecraft 26.2 official mappings,
JUnit 5.14, client-only Fabric APIs.

## 1. Confirmed root causes

### 1.1 Short paths are encoded as desired behavior

The configured range did not shrink. Both the responsive baseline and current
planner use 4,500 nodes, 28 horizontal blocks, and 8 vertical blocks.

The current `betterPartial` orders equal-risk endpoints by accumulated cost
before target heuristic. The test
`partialFrontierPrefersLowerCostBeforeTargetHeuristicAtEqualSurfaceRisk()`
therefore requires `(1,65,0)` even though `(3,65,1)` is connected and closer to
the target. Another test requires the planner to stop at a four-block cliff
frontier. The tests currently preserve the user's complaint as correctness.

### 1.2 Cliff proof exhausts almost the full search

`bestDropFrontier` is only returned at `visited >= maxNodes - 1`. The focused
cliff regression measured `10.704s` inside the test method. The behavior called
“before exhausting the node budget” actually waits until node 4,499 of 4,500.

### 1.3 Node-limit handling doubles aggressive work

An empty `NODE_LIMIT` is not executable. Because aggressive defaults enable
breaking and placement, `plan()` then starts a second complete search with a
larger `(position, breakCount, placeCount, dropRecoveryY)` state space. A budget
condition is incorrectly treated as proof that non-modifying movement failed.

### 1.4 Pipeline timing guarantees a gap for short segments

- Six aggressive ground steps take roughly 18–22 ticks.
- A successful initial installation sets `replanCooldown` to 50 ticks.
- Full snapshot capture is 57×57 = 3,249 columns.
- At 192 columns/tick capture needs at least 17 ticks.
- The worker search begins only after capture finishes.

If a five- or six-step continuation is unavailable, approximately 30 cooldown
ticks remain, followed by 17 capture ticks and the worker search. The observed
2.3–3 second pause follows directly from this state machine.

### 1.5 Initial capture can become stale while the player coasts

Aggressive movement sets horizontal delta directly. `stopMovement()` clears
input and sprint state but does not clear the previously assigned horizontal
delta. During a 17-tick initial capture the player can drift away from the raw
start. The squared-distance tolerance may accept a shifted start, after which
the first edge can be two blocks away or an invalid diagonal. Live validation
then calls `forceLocalReplan()`, clears every segment, and deterministic planning
can repeat the same visible one-block twitch.

### 1.6 Unloaded frontiers are intentionally unable to prefetch

`UNLOADED_FRONTIER` is executable but marked non-prefetchable. If the next
unloaded boundary is six blocks away, the planner returns a five-step segment
and the controller must run it dry even if the relevant chunk loads during
those five steps.

## 2. Objectives

### 2.1 Functional objectives

- Risk remains the primary frontier criterion; at equal risk, maximize verified
  target progress before comparing accumulated cost.
- Return an existing safe progress prefix when the node budget expires.
- Run modification-capable search only after a genuine exhaustive `NO_PATH`.
- Keep multi-block-drop debt and cave safety, but bound the work used to prove a
  detour and prune dominated debt states.
- Make successful segment exhaustion immediately eligible for continuation.
- Start safe lookahead with up to 28 remaining steps and capture 512 columns per
  tick.
- Refresh an unloaded frontier as soon as its seam-forward terrain becomes
  loaded; do not wait for the active segment to be empty.
- Validate and, where safe, trim an initial movement prefix against actual player
  feet before installing it.
- Eliminate residual horizontal coasting only in the no-waypoint safety state.
- Preserve every existing movement and packet speed.

### 2.2 Performance objectives

- Reduce the measured cliff planner method from `10.704s` to below one second on
  the same machine after JVM warm-up; deterministic expanded-node limits are the
  primary non-flaky assertion.
- Expand fewer than 1,000 nodes for the no-return cliff fixture and fewer than
  1,500 for the debt-free detour fixture.
- Complete a full 3,249-column capture in seven ticks at 512 columns/tick.
- Produce at least 20 steps on connected loaded surface terrain when the local
  target lies outside the snapshot.
- Execute a deterministic 96-block route made from six-step fake segments
  without a logical input-neutral seam when capture takes seven ticks and the
  fake planner takes five more ticks.
- Do not invoke a second modification search after `NODE_LIMIT`.

### 2.3 Non-goals

- This plan does not create a global or chunk-scale route planner.
- It does not increase the 28-block exact snapshot range.
- It does not allow blind movement while no verified waypoint exists.
- It does not lower danger, cave, collision, support, or drop safety.
- It does not change walking, sprinting, swimming, boat, Elytra, jump,
  placement, breaking, interaction, eating, or packet speeds.
- It does not add server-side code or protocol requirements.

## 3. Global constraints

- Minecraft `Level`, block, heightmap, chunk, player, and entity reads remain on
  the client thread.
- Worker jobs consume immutable snapshots and immutable requests only.
- Target/configuration/generation changes reject all old active, pending,
  buffered, and completed work before execution.
- A `SAFE_FRONTIER` is loaded, standable/swimmable, hazard-free, surface-like
  for a surface start, and free of unrecovered multi-block-drop debt.
- An `UNLOADED_FRONTIER` ends at the last verified safe point.
- World-modifying actions remain last-resort synchronous client-thread actions
  and block lookahead across unresolved changes.
- No fix may mask planning gaps by increasing velocity or packet frequency.

## 4. Design decisions

### 4.1 Frontier ordering

Use this lexicographic comparator:

```text
surface start:
  max covered depth
  accumulated underground exposure
  remaining target heuristic
  accumulated path cost
  deterministic position order

underground start:
  endpoint covered depth
  remaining target heuristic
  accumulated path cost
  deterministic position order
```

Drop debt is not part of the comparator because a node carrying debt is not a
valid partial endpoint. This keeps the existing hard safety rule while removing
the one-step cost bias.

### 4.2 Node-limit semantics

Search termination becomes:

```text
target reached                         -> REACHED_TARGET
safe loaded local seam                -> SAFE_FRONTIER
safe boundary adjacent to unloaded    -> UNLOADED_FRONTIER
budget reached + bestSafe exists      -> SAFE_FRONTIER(bestSafe)
budget reached + no bestSafe          -> NODE_LIMIT(empty)
open exhausted + bestSafe exists      -> SAFE_FRONTIER(bestSafe)
open exhausted + no bestSafe          -> NO_PATH(empty)
```

The public `plan()` may start the modification search only for `NO_PATH`.
`NODE_LIMIT`, any safe prefix, and any unloaded prefix return directly.

### 4.3 Drop-debt dominance and bounded proof

Keep `dropRecoveryY`, but prune states using a Pareto frontier keyed by:

```java
record BaseSearchKey(BlockPos pos, int breakCount, int placeCount) {}
record DebtState(int recoveryY, double cost) {}
```

Dominance rules at the same base key:

- `NO_DROP_DEBT` at lower or equal cost dominates every indebted state.
- An indebted state with a lower required recovery Y and lower or equal cost
  dominates a higher-recovery state.
- A popped priority-queue entry whose cost is no longer present in the Pareto
  set is stale and is not expanded.

Entering a two- or three-block debt adds `64.0` risk cost. This does not make the
drop legal as a frontier; it ensures reversible detours are explored first.

When the first safe cliff candidate is found, allow at most
`CLIFF_PROOF_NODE_BUDGET = 384` additional non-stale expansions to find a
debt-free route that makes better target progress. If none is found, return the
best ordinary safe candidate. A complete recovered path discovered inside the
proof budget remains valid.

### 4.4 Planning cadence

Replace the overloaded `replanCooldown` concept with explicit failure backoff:

```java
private int failedPlanRetryTicks;
```

- Success sets it to zero.
- Active or buffered work ignores it.
- `NO_PATH`, planner exception, or repeated invalid initial prefix sets it to 20
  ticks.
- `NODE_LIMIT` with no safe prefix sets a short 2-tick yield so the client does
  not submit every tick, but it never inherits the old 50-tick success delay.
- Force replan after live world change sets it to zero because a fresh snapshot
  is required immediately.

Add a pure policy for deterministic tests:

```java
public record NavigationPlanningCadence(
        int lookaheadRemainingSteps,
        int snapshotColumnsPerTick,
        int failedRetryTicks,
        int nodeLimitYieldTicks
) {
    public static NavigationPlanningCadence defaults() {
        return new NavigationPlanningCadence(28, 512, 20, 2);
    }
}
```

### 4.5 Segment continuation policy

Replace the ambiguous `prefetchable` boolean with:

```java
public enum ContinuationPolicy {
    NONE,
    IMMEDIATE,
    WHEN_TERRAIN_READY
}
```

`ContinuationPolicy` is a nested public enum of `PathSegmentCoordinator`; no
Minecraft type enters the core coordinator.

Mapping:

- `REACHED_TARGET` → `NONE`
- non-modifying `SAFE_FRONTIER` → `IMMEDIATE`
- non-modifying `UNLOADED_FRONTIER` → `WHEN_TERRAIN_READY`
- any segment containing BREAK/PLACE → `NONE`

The coordinator stays pure. The controller supplies `terrainReady` when asking
to begin lookahead. For `WHEN_TERRAIN_READY`, the seam, body/support cells, and
a target-directed probe four blocks beyond the seam must be in loaded chunks.
Once ready, capture begins before the active segment is empty.

### 4.6 Faster bounded capture

Advance exactly one capture per tick with a maximum of 512 columns. A 57×57
window therefore needs seven batches. Remove the duplicate defensive copy in
`NavigationSnapshotCapture.finish()`; pass the accumulated mutable collections
to `NavigationSnapshot`, whose canonical constructor performs the single
`Map.copyOf` / `Set.copyOf` freeze.

The total terrain read volume is unchanged. The change trades a bounded amount
of additional work per tick for ten fewer capture ticks; manual frame-time
testing is required before release.

### 4.7 Initial-prefix validation and twitch prevention

Introduce a pure structural validator:

```java
public final class InitialPathPrefixValidator {
    public PrefixDecision validate(
            PathSegmentCoordinator.Anchor actualFeet,
            PathSegmentCoordinator.Anchor plannedStart,
            List<InitialPathStep> steps,
            int maxProbeSteps
    );
}

public record PrefixDecision(PrefixStatus status, int trimCount) {}
public enum PrefixStatus { ACCEPT, TRIM, REJECT }
public record InitialPathStep(
        PathSegmentCoordinator.Anchor end,
        InitialStepKind kind
) {}
public enum InitialStepKind { WALK, JUMP, DROP, SWIM, BREAK, PLACE }
```

Rules:

- exact planned start accepts with trim count 0;
- if actual feet equals one of the first three non-modifying movement endpoints,
  trim the already-consumed prefix;
- otherwise the first remaining step must be one-column adjacent with a legal
  action delta;
- BREAK/PLACE prefixes are never structurally trimmed;
- the controller then applies existing live chunk/body/support/hazard/diagonal
  validation from actual feet.

Rejected initial plans are never installed. The controller stops aggressive
horizontal coasting by setting `(deltaX, deltaZ)` to zero while preserving
`deltaY`, then captures again from the actual feet without a successful-plan
cooldown. This brake is used only when no verified waypoint exists.

## 5. Data flow and state machine

```text
AUTO_WALK tick
  1. invalidate old target/config generation
  2. advance one capture by <=512 columns
  3. accept completed worker result
       initial: request checks -> structural trim -> live prefix validation
       lookahead: exact seam/request -> live seam + first 3 transitions
  4. if active suffix <=28:
       SAFE -> begin immediately
       UNLOADED -> begin when seam-forward probe is loaded
  5. if no active/buffer/capture/future:
       begin immediately unless a real failure backoff remains
  6. resolve current step or promote buffer in the same call
  7. if no verified step, brake residual horizontal delta and wait safely
  8. otherwise execute unchanged movement/action method
```

## 6. Detailed TDD implementation plan

### Task 1: Correct safe frontier progress and node-limit fallback

**Files:**

- Modify: `src/test/java/dev/mappywall/client/LocalPathPlannerTest.java`
- Modify: `src/client/java/dev/mappywall/client/LocalPathPlanner.java`

- [ ] Replace the cost-first regression with
  `partialFrontierMaximizesProgressAtEqualSafety()` and assert the farthest
  connected endpoint `(3,65,1)`.
- [ ] Add `nodeBudgetReturnsBestSafeProgress()` with a budget that finds multiple
  safe nodes but cannot reach the seam; assert non-empty `SAFE_FRONTIER` at the
  minimum remaining heuristic.
- [ ] Add `nodeLimitWithoutSafeProgressRemainsNonExecutable()` and assert empty
  `NODE_LIMIT`.
- [ ] Run:

```powershell
.\gradlew.bat test --tests "dev.mappywall.client.LocalPathPlannerTest" --no-daemon
```

  Expected RED: the first test receives the cheap one-step endpoint and the
  budget test receives empty `NODE_LIMIT`.
- [ ] Implement the comparator and termination table from sections 4.1–4.2.
- [ ] Re-run focused tests; expected GREEN.

### Task 2: Prevent node-limit modification search and bound drop proof

**Files:**

- Modify: `src/client/java/dev/mappywall/client/LocalPathPlanner.java`
- Modify: `src/test/java/dev/mappywall/client/LocalPathPlannerTest.java`

- [ ] Add `nodeLimitDoesNotTriggerModificationSearch()` with an aggressive
  config, a low deterministic node budget, and an allowed break obstacle; assert
  no BREAK/PLACE step is returned.
- [ ] Add `safeDetourBeatsNearbyCliffFrontierWithinProofBudget()` with a finite
  three-block cliff and a loaded debt-free gap; assert the route reaches beyond
  the cliff candidate without a multi-block DROP.
- [ ] Replace the current cliff test with
  `cliffFrontierUsesBoundedProofBudget()` and assert `expandedNodes < 1_000`.
- [ ] Add `dropDebtDominanceKeepsRecoveredRoute()` using the existing direct
  three-block drop versus one-block descent fixture.
- [ ] Extend `PathPlan` with an immutable `expandedNodes` record component and
  update every planner return site so performance tests observe deterministic
  search work rather than relying only on wall-clock timing.
- [ ] Run focused tests and verify RED, including the current ~4,499-node cliff
  behavior.
- [ ] Add Pareto dominance, stale-pop skipping, 64.0 debt cost, and the 384-node
  proof budget. Invoke modification search only for `NO_PATH`.
- [ ] Re-run all planner tests and verify the cliff method locally below one
  second after warm-up.

### Task 3: Separate failure retry from successful continuation

**Files:**

- Create: `src/main/java/dev/mappywall/core/NavigationPlanningCadence.java`
- Create: `src/test/java/dev/mappywall/core/NavigationPlanningCadenceTest.java`
- Modify: `src/client/java/dev/mappywall/client/MovementController.java`
- Modify: `src/test/java/dev/mappywall/core/NavigationContinuityScenarioTest.java`

- [ ] Write cadence tests for exact defaults `(28,512,20,2)` and constructor
  rejection of non-positive capture/lookahead values.
- [ ] Add
  `sixStepSegmentsRemainContinuousWithSevenTickCaptureAndFiveTickPlanner()` to
  the continuity scenario. Simulate sixteen six-step segments; start lookahead
  immediately and assert steps 1–96 are contiguous with no empty logical tick.
- [ ] Add `successfulSegmentExhaustionHasNoFailureBackoff()` and
  `noPathUsesTwentyTickFailureBackoff()`.
- [ ] Run focused tests; expected RED because cadence and short-segment lifecycle
  do not exist.
- [ ] Integrate the cadence and replace `replanCooldown` with
  `failedPlanRetryTicks` according to section 4.4.
- [ ] Re-run cadence/continuity tests; expected GREEN.

### Task 4: Accelerate snapshot freezing without worker world reads

**Files:**

- Modify: `src/client/java/dev/mappywall/client/NavigationSnapshotCapture.java`
- Modify: `src/test/java/dev/mappywall/client/NavigationSnapshotCaptureTest.java`
- Modify: `src/client/java/dev/mappywall/client/MovementController.java`

- [ ] Add `fullWindowCompletesInSevenControllerBatches()` using 3,249 columns
  and a 512 budget.
- [ ] Add `finishPerformsExactlyOneDefensiveFreeze()` through a counting fixture
  that proves `finish()` makes no world reads and returns the same immutable
  snapshot on repeated calls.
- [ ] Run focused tests and verify RED against the 192 controller constant or
  duplicate-copy implementation.
- [ ] Use the cadence's 512 budget and remove `Map.copyOf` / `Set.copyOf` calls
  from `finish()` while retaining the record constructor's defensive copies.
- [ ] Re-run capture tests and inspect code to prove no worker `Level` reads.

### Task 5: Make unloaded frontiers refreshable

**Files:**

- Modify: `src/main/java/dev/mappywall/core/PathSegmentCoordinator.java`
- Modify: `src/test/java/dev/mappywall/core/PathSegmentCoordinatorTest.java`
- Modify: `src/client/java/dev/mappywall/client/MovementController.java`
- Modify: `src/test/java/dev/mappywall/core/NavigationContinuityScenarioTest.java`

- [ ] Replace `Segment.prefetchable` with `ContinuationPolicy` and update existing
  test fixtures.
- [ ] Add `unloadedSegmentWaitsUntilTerrainReadyThenBeginsLookahead()`.
- [ ] Add `unloadedSixStepSegmentPromotesContinuationWithoutEmptyTick()` where
  terrain becomes ready at logical tick 3.
- [ ] Run focused tests and verify RED.
- [ ] Implement policy mapping and the controller's loaded seam/forward probe.
- [ ] Keep modifying suffixes at `NONE` regardless of outcome.
- [ ] Re-run coordinator and continuity tests; expected GREEN.

### Task 6: Validate and trim initial prefixes before installation

**Files:**

- Create: `src/main/java/dev/mappywall/core/InitialPathPrefixValidator.java`
- Create: `src/test/java/dev/mappywall/core/InitialPathPrefixValidatorTest.java`
- Modify: `src/client/java/dev/mappywall/client/MovementController.java`

- [ ] Write RED tests:
  `acceptsExactStart`,
  `trimsConsumedMovementPrefix`,
  `rejectsTwoColumnFirstTransition`,
  `rejectsUnsafeDiagonalStructure`, and
  `neverTrimsModificationAction`.
- [ ] Implement the pure validator with a maximum probe of three steps.
- [ ] Before `installInitial`, apply structural trim and current live validation.
- [ ] Add a controller-adjacent helper test proving a rejected prefix does not
  install an active segment and requests a fresh capture without success
  cooldown.
- [ ] Add a narrowly named `stopForPlanningGap` method that calls existing
  `stopMovement` and clears only horizontal delta in aggressive mode.
- [ ] Re-run focused core/client tests; expected GREEN.

### Task 7: Integration, performance gates, documentation, and release

**Files:**

- Modify: `docs/design.md`
- Modify: `gradle.properties`
- Modify: relevant test files above only if integration exposes a missing
  behavior assertion.

- [ ] Run focused planner tests twice with `--rerun-tasks`; inspect XML and record
  the warmed cliff duration and expanded-node count.
- [ ] Run:

```powershell
.\gradlew.bat test --tests "dev.mappywall.client.*" --tests "dev.mappywall.core.PathSegmentCoordinatorTest" --tests "dev.mappywall.core.NavigationContinuityScenarioTest" --no-daemon
.\gradlew.bat test --no-daemon
.\gradlew.bat clean build --no-daemon
git diff --check
```

- [ ] Parse all Fabric, mixin, and language JSON resources.
- [ ] Inspect XML for zero failures/errors and inspect the release jar for all
  changed/new classes.
- [ ] Verify by diff that movement speed constants, movement methods, boat,
  Elytra, breaking, placement, and packet methods have no changed lines except
  the new no-waypoint brake call site/helper.
- [ ] Update `docs/design.md` with the repaired state machine and bump the patch
  version after tests pass.
- [ ] Request independent review focused on frontier safety, bounded search,
  failure backoff, unloaded refresh, stale-prefix trimming, worker isolation,
  and unchanged normal movement/packet behavior.

## 7. Acceptance matrix

| Scenario | Required result |
| --- | --- |
| Equal-risk connected surface | Farthest verified progress, not cheapest first step |
| No-return three-block cliff | Bounded safe frontier, no DROP debt, <1,000 expansions |
| Three-block cliff with safe gap | Debt-free detour reaches beyond nearby edge |
| Covered shortcut versus surface route | Surface route wins |
| Short tunnel with proven exit | Complete route may use tunnel |
| `NODE_LIMIT` with safe prefix | Return executable prefix; no modification retry |
| `NODE_LIMIT` without safe prefix | Empty bounded result and 2-tick yield |
| True `NO_PATH` in aggressive mode | Only then consider allowed BREAK/PLACE fallback |
| Six-step SAFE segments | Continuous 96-step simulated execution |
| Six-step UNLOADED segment | Refresh when chunks load; no forced exhaustion gap |
| Player drifts during initial capture | Prefix trimmed or rejected before installation |
| No verified waypoint | Safe horizontal brake; no repeated coasting/replan twitch |
| Target/config changes | All old results rejected |
| Free-look/inventory aggressive behavior | Unchanged |
| Boat/swim/Elytra | Existing behavior and speed unchanged |

## 8. Manual game validation

Use aggressive mode with HUD/debug metrics available only in logs:

1. Run at least 200 blocks over ordinary rolling surface terrain; record local
   segment lengths, capture ticks, worker duration, and seam gaps.
2. Approach a cave entrance with a surface detour; confirm the surface route is
   selected.
3. Approach a two-/three-block cliff with and without a nearby safe route;
   confirm detour versus one stable safe stop.
4. Travel toward slowly loading chunks; confirm unloaded continuation starts
   once the target-directed probe loads.
5. Force collision sliding beside a one-block wall; confirm no repeated
   install/reject loop.
6. Test swimming and a boat to confirm the planning changes did not modify
   paddle or swim cadence.
7. Open inventory and free-look during aggressive travel to confirm travel
   direction remains independent of the camera.

Release acceptance requires no repeated five-/six-step planning cycle on a
connected loaded surface and no observed one-block stop/start loop in these
scenarios.

## 9. Risks and mitigations

- **512 columns/tick can increase a single tick's terrain-copy cost.** The total
  capture is unchanged; profile frame time and fall back to 384 if 512 causes a
  repeatable spike while retaining the cadence abstraction.
- **A 384-node cliff proof can miss an unusually long local detour.** The best
  safe progress remains valid, and option 3 exists for larger-scale route
  planning. The bound is preferable to a 10-second local search.
- **Progress-first frontier may choose a longer path.** Risk remains first, and
  cost still resolves equal-progress ties.
- **Pareto dominance may incorrectly discard a recoverable state if specified
  wrongly.** Dedicated debt-state fixtures and expanded-node diagnostics are
  required before integration.
- **Trimming a prefix could skip a modification acknowledgement.** The validator
  never trims BREAK/PLACE actions.
- **Horizontal braking affects stopping feel.** It is limited to the state where
  no verified waypoint exists and does not alter cruise logic or constants.

## 10. Migration, rollback, and observability

No project save or navigation-config schema changes are required. All new
policy objects use code defaults.

During manual testing, record these diagnostics at debug level:

- path outcome and step count;
- expanded nodes and planner nanoseconds;
- capture columns and ticks;
- active remaining steps and continuation policy;
- lookahead rejection reason;
- initial prefix decision and trim count;
- force-replan reason.

Rollback can be task-scoped:

1. reduce capture budget if frame time regresses;
2. disable unloaded refresh while retaining SAFE lookahead;
3. revert prefix trimming while keeping pre-install validation;
4. revert the branch and select `26.2/navigation-fast-rollback`.

No rollback requires save migration or server changes.

## 11. Decision summary

This is the preferred branch because it addresses every confirmed regression at
its source while keeping the current safety model. It is larger than a direct
rollback but substantially smaller and less risky than a hierarchical rewrite.
Its success criteria are measurable in deterministic tests and directly match
the reported five-/six-step, three-second, and one-block-twitch failures.
