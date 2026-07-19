# Partial-Step Execution and Water Transit Design

## Objective

Repair two aggressive-mode navigation defects while preserving vanilla movement limits and client-only server compatibility:

- a grounded player must be able to move from a partial-height support such as farmland or a dirt path onto an adjacent full block;
- automatic water travel must acquire a boat reliably for confirmed long crossings, while short crossings remain ordinary swimming.

The default minimum confirmed water-path distance for acquiring a boat is 12 blocks. The threshold applies equally to boarding a nearby empty boat and placing a carried boat.

## Confirmed root causes

### Partial-height support to full-block stall

Farmland and dirt paths in Minecraft 26.2 have a 15/16-block collision height. A player standing on either has a physical feet Y ending in `0.9375`, while `NavigationFeetResolver` correctly maps the player to the integer logical feet cell above that support. The planner consequently emits a same-level `WALK` into an adjacent full-block column, and live step validation correctly accepts it.

Aggressive execution then calls `collisionAdjustedHorizontalVelocity()` before vanilla entity movement. That method tests the player's physical AABB after a purely horizontal translation. The lower AABB still begins 1/16 block below the full block's top, so the direct and single-axis probes report a collision and the requested horizontal velocity is reduced to zero.

Vanilla `Entity` movement would normally resolve this contact through its bounded `maxUpStep()` branch. Pre-clipping the velocity prevents that branch from receiving any horizontal movement. The waypoint therefore remains incomplete, stall accounting eventually replans, and the same valid logical path is selected again.

This is an execution-layer defect. Increasing waypoint tolerances, changing logical feet, or converting the edge to `JUMP` would hide the symptom while changing valid movement semantics.

### Water paths bypass boat acquisition

`LocalPathPlanner.swimmable()` permits a `SWIM` waypoint whose block and block above are both water. `MovementController.swimOrBoat()` attempts boat acquisition only when the current waypoint itself is the top water block. A path running one or more blocks below the surface therefore permanently bypasses both nearby-boat boarding and boat placement.

The placement path has a separate reliability defect. `bestBoatWaterPos()` may choose a submerged, obstructed, or route-unrelated water cell; `useBoatItemAtWater()` ignores the interaction result and unconditionally starts a 40-tick cooldown. The controller does not distinguish a rejected placement from a server-confirmed boat entity, so a failed attempt silently falls back to swimming without a bounded confirmation or retry policy.

There is currently no minimum water distance. Every eligible surface `SWIM` step may attempt to board or place a boat, including crossings only a few blocks wide.

## Design 1: Vanilla-resolved grounded stepping

Aggressive ground movement will continue to calculate and send the existing server-facing direction, sprint state, and bounded speed. For an on-ground player executing an ordinary validated `WALK`, it will no longer pre-clip the requested horizontal velocity with the simplified AABB projection. The velocity is passed to vanilla entity movement so vanilla can perform its normal axis resolution and bounded automatic step-up.

The existing pre-execution safeguards remain authoritative:

- the waypoint must be adjacent to the resolved logical feet cell;
- diagonal side cells must be safe;
- target body and head space must be collision-free;
- target support must be solid and non-hazardous;
- `WALK`, `JUMP`, and `DROP` retain their existing integer vertical limits;
- ordinary collision/stall recovery still replans when a real obstruction prevents progress.

Boat velocity keeps the existing collision projection. Airborne, swimming, sneaking/drop-commit, and dismount-recovery movement retain their dedicated handling. The change does not set vertical velocity, synthesize a jump, increase `maxUpStep`, or permit any transition vanilla movement would reject.

The rejected alternatives are:

- duplicating vanilla step-up collision search inside MappyWall, which would be fragile around corners, stairs, entity collision, and future Minecraft changes;
- teaching the coarse planner to label a 1/16 transition as `JUMP`, which would create visible hopping and require collision-shape metadata throughout the immutable snapshot.

## Design 2: Confirmed water-run evidence

Introduce a pure `WaterTransitPolicy` and a client-thread adapter. Boat acquisition is based only on already accepted route evidence, never on a guessed straight-line scan.

The adapter scans from the current `SWIM` waypoint through `PathSegmentCoordinator.previewStepSnapshot()`. This includes the active suffix and an accepted `CONTINUOUS` buffered segment, but excludes pending work and hidden retreat-required buffers.

For every consecutive `SWIM` waypoint, the adapter resolves its column upward to the top of the contiguous water column and validates a boatable surface:

- the relevant chunks and cells are loaded;
- the top cell is water and the cell above is not water;
- the space required by the boat is not blocked;
- consecutive resolved surfaces remain navigably level;
- the resolved surface belongs to the accepted path corridor.

Confirmed traversal length includes the edge from the current logical feet position (or the preceding accepted non-water step) into the first `SWIM` waypoint, followed by the edges between consecutive `SWIM` waypoints. Cardinal edges contribute `1.0` and diagonal edges contribute `sqrt(2)`. This makes a 12-block-wide cardinal crossing meet the threshold at its twelfth water block. A non-`SWIM` step, an unloaded column, a non-boatable surface, an incompatible surface height, or the end of confirmed route evidence terminates the proof.

The policy returns:

- `SWIM` when confirmed distance is less than 12 blocks;
- `ACQUIRE_BOAT` once confirmed distance reaches 12 blocks;
- `CONTINUE_RIDING` when the player is already riding a boat.

Unknown terrain is not counted. If the current accepted path proves only 8 water blocks and ends at an unloaded frontier, the player continues swimming while lookahead proceeds. If a later accepted continuous buffer raises the confirmed distance to 12, acquisition becomes eligible at that time.

Eligibility is latched for the current continuous water run. Advancing far enough that the remaining visible suffix drops below 12 does not cancel an acquisition already justified by the original confirmed run. The latch resets on leaving the water run, changing navigation target/generation, forcing a route reset, or entering a terminal controller state. It does not cause a mounted boat to dismount near shore; existing boat-to-land recovery remains responsible for that transition.

Short water runs neither board a nearby empty boat nor place a carried boat.

## Design 3: Confirmed boat acquisition

Boat acquisition is a non-blocking side process. Swimming continues unless an actual interaction needs a single neutral transaction tick, so failure cannot create a navigation stall.

The bounded acquisition phases are:

1. `IDLE`: no eligible long water run.
2. `SELECTING`: prefer a nearby alive, empty, reachable boat that is not suppressed by dismount recovery; otherwise select or move a carried `BoatItem` to the hotbar and wait for the inventory state to confirm it is held.
3. `PLACING`: choose a reachable validated surface cell from the confirmed route corridor, orient the server-facing interaction without changing the user's aggressive-mode camera, and send one use action.
4. `AWAITING_ENTITY`: only an accepted interaction enters this phase. Continue safe swimming while waiting for a nearby newly visible empty boat.
5. `BOARDING`: interact with the confirmed nearby boat and wait for passenger state.
6. `FALLBACK`: after a bounded timeout or bounded failed attempts, suppress further acquisition for this water run and continue swimming.

`PASS`, `FAIL`, a missing held boat, an invalidated placement surface, or an unavailable GUI transaction does not enter the old unconditional 40-tick success cooldown. Closing an inventory or other screen may make acquisition eligible again, but opening a screen never stops aggressive-mode water movement.

The existing priority remains: nearby empty boat, then carried boat, then swimming. A successfully placed boat must be observed as an entity before boarding is considered successful. Server rejection, delayed inventory synchronization, and entity-spawn delay are explicit observations rather than inferred success.

## Configuration and migration

Add a persisted integer minimum boat distance with a default of 12 blocks and an accepted range of 1 through 128. Expose it in the existing navigation settings screen as a numeric field. Aggressive mode uses the persisted value; normal mode retains the same default unless its configuration is later exposed separately.

Loading an older `navigation.json` that lacks the field must explicitly supply 12 rather than accepting Gson's missing primitive value of zero. Invalid values are normalized safely without discarding the user's unrelated breaking, placement, food, whitelist, or blacklist settings. Resetting navigation settings restores the 12-block default together with the existing defaults.

## Component boundaries

- `MovementController`: samples live collision/water/entity/inventory state, delegates pure decisions, executes vanilla-compatible input and interaction actions, and owns per-water-run acquisition state.
- `WaterTransitPolicy`: pure threshold, latch, phase, timeout, and fallback decisions with no Minecraft dependency.
- `PathSegmentCoordinator`: unchanged ownership of accepted active and buffered routes; its preview snapshot supplies action-preserving confirmed evidence.
- `NavigationFeetResolver`: unchanged logical coordinate source.
- `AutoNavigationConfig` and `NavigationConfigStore`: threshold persistence, validation, and old-file migration.
- `NavigationSettingsScreen`: numeric threshold editing and validation feedback.

## Safety and performance constraints

- No server-side mod, custom packet, entity, item, or block is introduced.
- No walking, sprinting, swimming, boat, jump, placement, breaking, or packet-rate constant is increased.
- The partial-step fix delegates to vanilla `maxUpStep`; it does not change that value or inject vertical motion.
- World, collision, entity, chunk, inventory, and interaction-result reads remain on the client thread.
- Water-run analysis is linear in the already bounded visible path and stops as soon as 12 blocks are proved; it does not add a world search.
- Pending, stale, unloaded, or hidden retreat paths cannot justify acquiring a boat.
- Acquisition failure always falls back to continued swimming or an existing safe terminal state; it cannot loop indefinitely or repeatedly consume boats.
- Dismount recovery and original-boat reboarding suppression remain authoritative.

## Automated acceptance

### Partial-height stepping

- a grounded aggressive player request from a 15/16-height support toward an adjacent full-block support retains its horizontal intent for vanilla resolution;
- flat full-block travel retains the same aggressive speed and sprint state;
- a real wall, unsafe target, unsafe diagonal, excessive integer height, or missing support still fails validation or is stopped by vanilla collision and bounded recovery;
- normal mode, swimming, boat driving, drop commitment, and dismount recovery retain their existing execution paths;
- no test solves the case by enlarging waypoint Y tolerances or emitting `JUMP`.

### Water-run policy

- confirmed distances below 12 choose `SWIM`, while exactly 12 and greater choose `ACQUIRE_BOAT`;
- diagonal distances use geometric horizontal length;
- a submerged `SWIM` waypoint can resolve to the valid surface of its water column;
- covered, obstructed, incompatible-height, non-water, and unloaded columns terminate evidence;
- an accepted continuous buffer can complete the 12-block proof, while pending and hidden-retreat work cannot;
- eligibility remains latched after the remaining suffix becomes shorter than 12 and resets at the water-run boundary;
- short water runs do not board existing boats.

### Acquisition

- a nearby eligible empty boat is preferred over placing an inventory boat;
- one inventory swap is followed by held-item confirmation rather than repeated swaps;
- an invalid surface or rejected interaction does not start a success cooldown;
- an accepted placement waits for an observed boat entity before boarding;
- entity confirmation and passenger confirmation advance the state exactly once;
- placement timeout and bounded repeated failure suppress acquisition for that water run and continue swimming;
- target reset, pause, hard reset, and world change clear acquisition state.

### Configuration

- a missing threshold in an old configuration migrates to 12 while retaining all existing fields;
- valid custom values round-trip through persistence and the settings screen;
- malformed or out-of-range values normalize to a safe value;
- resetting restores 12.

## Manual validation

Use aggressive mode in an integrated or vanilla-compatible server world:

1. Run from farmland and a dirt path onto full blocks from cardinal and shallow diagonal approaches; confirm continuous motion without hopping or repeated replanning.
2. Repeat against a genuine wall and a one-block upward route; confirm the wall remains blocked and the planned one-block climb still uses the normal jump path.
3. Cross water runs of 3, 11, 12, and more than 12 blocks with a boat in the inventory; confirm only the last two acquire a boat.
4. Repeat a long crossing whose accepted route initially ends before 12 blocks and later receives a rolling continuation; confirm swimming continues first and acquisition begins only after enough route is confirmed.
5. Test a deep lake whose `SWIM` waypoints are below the top water layer; confirm the controller resolves the actual surface and places a boat.
6. Test a low bridge, covered water, changing water levels, an open inventory screen, a rejected placement, and delayed entity spawn; confirm bounded fallback and uninterrupted swimming.
7. Reach shore in the acquired boat and confirm the existing server-confirmed dismount recovery still completes without reboarding or hop/replan loops.

## Non-goals

- This change does not redesign the local path search or its water terrain cost.
- It does not predict water beyond loaded, accepted route evidence.
- It does not make boats, create server-side behavior, or guarantee a boat is available.
- It does not change boat steering speed or shore dismount behavior.
- It does not add full sub-block collision shapes to planner snapshots.
- It does not change map opening, filling, binding, conflict handling, or HUD style text.
