# Safe and Seamless Navigation Design

## Objective

Improve automatic walking without changing its vanilla sprint behavior:

- prefer routes that remain on safe, reversible surface terrain;
- enter a covered or underground passage only when the current verified plan also proves a safe exit or reaches a valid navigation target;
- never commit an incomplete local segment that leaves an unrecovered two- or three-block drop;
- calculate the next local segment while the current segment is still being executed, then switch segments without a neutral movement tick.

## Confirmed root causes

`LocalPathPlanner` searches only a bounded 28-block horizontal neighborhood. When the final target lies outside that neighborhood, it currently returns whichever visited node has the smallest remaining Euclidean distance. The endpoint selection ignores cumulative risk, surface depth, irreversible drops, and whether the endpoint is a safe place from which to continue. Replanning also resets the target height to the player's new height, allowing repeated segments to ratchet downward into a cave.

`MovementController` has one active path and one future. It does not request the next segment until the active path is exhausted. The empty-path branch releases movement while the new snapshot and search are prepared. A result planned early from the moving player's position would be rejected by the existing start-drift check, so seamless movement requires a stable seam anchor rather than merely submitting the same future sooner.

## Route safety model

The navigation snapshot records, per loaded horizontal column, a surface reference height obtained from the world's motion-blocking heightmap. The path model derives a covered-depth risk from the difference between this reference and the candidate feet position. A shallow roof, foliage, or brief overhang is not sufficient by itself to classify a route as a cave; risk grows only with sustained covered depth, downward progression, and lack of a safe continuation.

Search nodes track:

- cumulative movement cost;
- maximum covered depth and accumulated underground exposure;
- unrecovered-drop debt, created by drops greater than one block and cleared only after the path regains the pre-drop height on a safe node;
- whether the node is a safe surface-like continuation point.

A complete path may traverse a covered passage when it reaches a valid surface-like target or returns to a safe surface-like node after the passage. An incomplete path may be returned only as a `SAFE_FRONTIER`: its endpoint must be loaded, standable, surface-like, and free of unrecovered-drop debt. Partial endpoint ranking is risk-first, then accumulated cost, then remaining target distance. A locally closer cave endpoint therefore cannot beat a longer safe surface frontier.

Search results distinguish `REACHED_TARGET`, `SAFE_FRONTIER`, `UNLOADED_FRONTIER`, `NODE_LIMIT`, and `NO_PATH`. The controller must not execute `NODE_LIMIT` or `NO_PATH` as if they were valid forward progress. `UNLOADED_FRONTIER` waits only at the last verified safe point.

When the player is already underground, the risk model may accept temporary horizontal backtracking or movement away from the map target when it reduces covered depth or returns toward the last safe altitude. It must not break farther into a covered dead end merely because doing so reduces horizontal heuristic distance.

Dimensions without a meaningful open-sky surface use the same reversible-frontier and drop-debt rules but omit the heightmap surface preference.

## Seamless segment coordination

A pure Java `PathSegmentCoordinator` owns active, pending, and buffered segment metadata without depending on Minecraft classes. It uses a stable target generation and a seam anchor for every lookahead request.

While an active segment has approximately 12–16 safe movement steps remaining, the controller starts building a snapshot around that segment's planned endpoint. Snapshot capture stays on the client thread but is divided into bounded column batches across ticks. Once complete, immutable data is submitted to the existing planner executor.

The current segment remains active while lookahead capture and search run. A completed continuation is accepted only when:

- target key and generation still match;
- its start equals the requested seam anchor;
- the active suffix still ends at that seam;
- the seam and the continuation's first live steps pass current-world validation;
- the active suffix contains no unresolved `BREAK` or `PLACE` action whose result would change the future snapshot.

At the seam, a buffered continuation is promoted in the same client tick. No input-neutral state is emitted between the last step of the old segment and the first step of the new segment. A target change invalidates active, pending, and buffered data immediately, preserving the existing rule that no old-region waypoint may execute for a new target.

If lookahead cannot finish because required chunks are unloaded, the player continues along the current verified segment and stops only at its safe endpoint. Chunk availability invalidates the empty-result cooldown so planning may retry promptly.

## Component boundaries

- `LocalPathPlanner`: immutable terrain search, risk accounting, safe-frontier selection, and explicit outcome.
- `PathSegmentCoordinator`: testable active/pending/buffered state transitions and target-generation checks.
- `NavigationSnapshotCapture`: incremental client-thread snapshot construction for an arbitrary seam anchor.
- `MovementController`: live validation, action execution, coordinator integration, and safe fallback.

The existing sprint, swimming, boat, Elytra, block-placement, and block-breaking speeds are not changed by this work.

## Verification

Deterministic tests must cover:

- a closer cave dead end losing to a longer reversible surface route;
- an incomplete plan refusing an unrecovered two- or three-block drop;
- a covered passage being accepted when the same plan proves its surface exit;
- underground recovery being allowed to move temporarily away from the horizontal target;
- explicit differentiation of safe, unloaded, exhausted, and impossible results;
- active movement continuing while lookahead is pending;
- atomic promotion at a seam with no neutral tick;
- stale target generations and invalid seams being rejected;
- no prefetch across unresolved world-modifying actions;
- a route longer than 60 blocks remaining continuous with a delayed fake planner.

The final verification includes the full unit suite, client compilation, a clean Gradle build, JSON/resource checks, and an independent code review focused on safety and asynchronous state transitions.
