# Navigation Continuity and Support Recovery Design

## Objective

Repair three remaining aggressive-mode navigation defects without changing travel speeds or weakening collision safety:

- a rolling continuation must not make a U-turn at the yellow-line seam when a forward detour exists;
- an accepted forward continuation must become visible before the active yellow line is exhausted;
- dismounting a boat must not enter a jump/replan loop while the player is still standing on or intersecting the boat;
- farmland, soul sand, dirt paths, slabs, stairs, and snow layers must not cause perpetual initial-prefix rejection.

## Confirmed root causes

### Segment-level U-turns

Lookahead planning is already concurrent with movement. When the active segment has at most 28 steps remaining, the controller incrementally captures terrain and submits a worker plan from the future seam. The accepted continuation is stored in `PathSegmentCoordinator.buffered`.

The continuation request contains only its seam and final target. It does not contain the active segment's incoming direction or recent tail. The local planner therefore treats the seam as a new isolated start and may immediately reverse into recently traversed terrain. The coordinator validates the exact seam but has no continuity classification.

The renderer is independently hiding all buffered work: `remainingStepSnapshot()` returns only the active suffix, so `MovementController.pathSnapshot()` cannot expose an already accepted continuation. The new line appears only after promotion, making the U-turn appear precisely when the old line ends.

### Partial-height support loops

The planner uses integer navigation feet cells, while several controller call sites use raw `player.blockPosition()`. On farmland at block Y=63, for example, the stable player feet are at Y=63.9375 and `blockPosition()` remains Y=63. The snapshot marks the farmland cell non-passable and `stableFeetPos()` moves the planner start to Y=64.

The controller then compares the raw Y=63 position with a plan starting at Y=64. A flat `WALK` edge is misclassified as a one-block climb, rejected by initial-prefix validation, and resubmitted indefinitely. The same mismatch appears in live step validation, projected-support validation, and waypoint completion. Fixed numeric tolerances cannot cover the full collision-height range and would weaken real jump/drop checks.

### Boat dismount loops

The current controller sends sneak and also calls `player.stopRiding()` immediately. The client can therefore enter a detached state before the server's boat-specific safe dismount location is received. During this interval the player can remain on or overlap the former boat.

Terrain snapshots contain block and fluid collision data but not entity support. Planning may approve a block-grid `WALK`, `JUMP`, or `SWIM`, while aggressive movement correctly sees the boat entity collision. A normal step is reduced to zero velocity; a jump bypasses projected block support and produces the observed half-block hop. Stall recovery clears the path, and the same incomplete world model recreates it.

## Design 1: Continuity-aware rolling planning

### Immutable continuation context

Every lookahead request carries an immutable continuity context derived from the future active suffix, never from the player's current location:

- the seam;
- the incoming direction at the seam;
- up to eight recent non-modifying path positions ending immediately before the seam.

The planner receives this context only for lookahead. Initial plans and underground recovery without a prior segment retain their existing behavior.

### Two-stage search

The primary continuation search applies a continuity guard:

- the first movement edge may not immediately return to the penultimate active node;
- a candidate may not re-enter the recent tail;
- the beginning of a continuation may not cross materially behind the seam's incoming plane and form a U-turn corridor.

This is not a global monotonic-distance rule. Sideways detours, temporary target-distance increases, cave exits, and safe obstacle avoidance remain legal.

If and only if the guarded search exhausts its open set with `NO_PATH`, the planner may retry without the guard. `NODE_LIMIT` is not proof that retreat is required and must not trigger a second search. A guarded result is `CONTINUOUS`; a result that actually uses the relaxed area is `RETREAT_REQUIRED`.

This preserves completeness in genuine dead ends while preventing deterministic reject/replan loops. It also confines the extra search cost to proven local dead ends.

### Preview semantics

The coordinator exposes a display snapshot distinct from its execution snapshot:

- pending, stale, or live-rejected work is never displayed;
- an accepted `CONTINUOUS` buffer is appended to the active remaining steps immediately;
- an accepted `RETREAT_REQUIRED` buffer remains hidden until normal promotion;
- target reset, forced replan, and generation invalidation clear both execution and display state atomically;
- promotion does not duplicate the seam or create an empty rendered frame.

Execution still promotes only at the seam. Rendering a buffer never authorizes early execution.

## Design 2: Canonical logical feet

Introduce one `PlayerNavigationFeet` resolver as the only source of a player's integer path-grid origin.

For a grounded, non-swimming, non-passenger player, compute the candidate grid Y as `ceil(physicalFeetY - epsilon)`. If that differs from the raw floored Y, return the candidate only when its block below is loaded and has a non-empty block collision shape. Otherwise retain the raw cell. This maps partial-height block support to the planner's integer feet cell while leaving boat/entity support unmodified because water or air below the candidate has no block collision.

The resolver answers coordinate truth only. Existing body-clearance, hazard, and unsafe-support checks remain responsible for legality, so fences, walls, panes, bars, chains, and pointed dripstone do not become traversable merely because their physical feet coordinate was normalized. Airborne, swimming, climbing, passenger, and entity-supported transition states keep their raw semantics and are handled by their dedicated controllers.

The normalized position is used consistently for:

- initial snapshot seeds;
- planning-start drift checks;
- initial-prefix preparation and live validation;
- movement-step vertical deltas and diagonal origins;
- modification-step adjacency;
- non-water entry-height comparisons;
- grounded waypoint Y completion;
- projected-support checks;
- any navigation target calculation whose Y origin depends on player feet.

The resolver deliberately does not make collision blocks passable and does not change planner action limits. It aligns the controller with the planner's existing coarse voxel model. Grounded waypoint completion compares logical feet Y; airborne and swimming completion continues to use physical entity Y.

## Design 3: Server-confirmed dismount recovery

Introduce a pure `DismountRecoveryPolicy` and a small Minecraft adapter.

The state machine has four phases:

1. `IDLE`: ordinary navigation.
2. `REQUESTED`: neutralize boat/player input, send the dismount input, retain the former boat identity, and wait for passenger state to clear. Do not force local `stopRiding()`.
3. `EGRESS`: while the player overlaps or is supported only by the former boat, suspend normal planning, ordinary stall counters, and automatic reboarding of that boat. Select a bounded adjacent egress candidate toward the navigation target when possible.
4. `RESUME`: after the player is clear of the former boat and has stable block support or safe water, clear recovery state and request exactly one fresh plan.

An egress candidate must have a collision-free full player AABB, clear body space, and either safe solid support or safe water. Entity collision remains enabled. If no candidate becomes usable within a bounded timeout, automation pauses safely instead of jumping and replanning forever.

Fast server correction is a normal path: if detachment places the player directly on safe ground or water, the state machine advances from `REQUESTED` to `RESUME` without visible movement.

## Component boundaries

- `LocalPathPlanner`: guarded and relaxed continuation search plus continuity classification.
- `PathSegmentCoordinator`: immutable active/pending/buffered lifecycle and display snapshot policy.
- `PlayerNavigationFeet`: live-world conversion from physical player support to integer path-grid feet.
- `DismountRecoveryPolicy`: pure phase transitions and decisions with no Minecraft dependency.
- `MovementController`: client-thread world sampling, policy integration, safe movement execution, and one-shot replanning.
- `WorldTargetRenderer`: unchanged line drawing; it receives a richer already-validated path snapshot.

## Safety and performance constraints

- Minecraft world, collision, entity, player, and chunk reads remain on the client thread.
- Worker planning consumes immutable snapshots and immutable continuation context only.
- `NODE_LIMIT` never starts a relaxed continuity search.
- Necessary retreat remains possible only after an exhaustive guarded `NO_PATH`.
- Displaying a buffer does not promote or execute it.
- No change may increase movement, swimming, boat, jump, placement, breaking, or packet speeds.
- No entity collision may be ignored during dismount recovery.
- No fixed sleep substitutes for an observed passenger/support/collision transition.
- No save schema or server-side component is added.

## Automated acceptance

### Continuity

- a straight continuation cannot reverse into its penultimate node;
- a forward detour is selected instead of any route that re-enters the recent eight-node tail;
- an unavoidable dead-end retreat succeeds only through the relaxed `NO_PATH` fallback and is classified `RETREAT_REQUIRED`;
- `NODE_LIMIT` does not run the relaxed search;
- an accepted continuous buffer appears in the display snapshot before promotion;
- retreat-required, stale, pending, and rejected buffers do not appear early;
- promotion and reset produce no duplicate seam or stale line.

### Logical feet

- full blocks, farmland, soul sand, dirt paths, bottom/top slabs, lower/upper stairs, and one/two/seven snow layers resolve consistently;
- water, airborne, passenger/entity support do not receive block-ground normalization, while blocked headroom and unsafe narrow support remain normalized coordinates but fail the existing legality checks;
- a farmland start that previously rejected a flat first `WALK` now installs and validates it;
- grounded partial-height waypoints complete without enlarging jump/drop tolerances;
- projected movement across equal partial-height supports is not zeroed.

### Dismount

- requesting dismount while still a passenger cannot start ordinary planning;
- detached overlap with the former boat cannot enter ordinary stall recovery or reboard that boat;
- a safe ground/water egress is selected and executed with entity collision intact;
- stable clearance requests one and only one fresh plan;
- direct server placement on shore resumes immediately;
- an unavailable egress times out to a safe pause rather than an infinite loop.

## Manual validation

Use aggressive mode on a vanilla server or integrated vanilla-compatible world:

1. Travel across at least three rolling local segments and confirm the next yellow continuation appears before the current line ends, with no seam U-turn.
2. Verify a real dead end can still retreat and that its retreat line does not appear prematurely.
3. Drive a boat to shore, dismount onto the boat edge, and confirm the controller waits or exits once without repeated half-block hops.
4. Walk continuously across farmland, soul sand, dirt path, slabs, stairs, carpet/snow transitions, and confirm no repeated planning state.
5. Recheck cave avoidance, cliff/drop safety, breaking/placement priority, free-look, open-inventory behavior, swimming, and boat travel cadence.

## Non-goals

- This change does not create a global or hierarchical route planner.
- It does not guarantee a globally shortest route.
- It does not forbid retreat when terrain proves retreat is necessary.
- It does not redesign sub-block surface-height costs; it fixes consistency with the existing integer navigation grid.
- It does not alter HUD style text or map-opening workflows.
