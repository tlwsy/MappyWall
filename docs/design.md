# MappyWall Design Notes

MappyWall is a client-only Fabric mod for planning and opening map walls on vanilla-compatible servers.

Current implementation targets Minecraft `26.2` with its official unobfuscated names and Java 25.

## Current boundaries

- Manual routing is available and remains the safest/default mode.
- Automatic walking is available as an explicit mode. It uses vanilla-compatible client inputs, movement packets, and interactions, including sprinting, jumping, conservative local path planning, opt-in block breaking/placing, eating, and optional boat use when water is encountered.
- Elytra/firework automation is available as an explicit automatic mode, including a separately visible aggressive style.
- The core planner has no Minecraft dependencies so route math, binding recovery, and persistence can be tested without launching the game.
- Client integration must not register blocks, items, entities, server packets, or any behavior that assumes a Fabric server.

## Map wall model

The first planned map is anchored to the map region containing the player when the project is created. Wall columns move east (`+X`) and rows move south (`+Z`). The MVP route uses a snake pattern to reduce travel distance while preserving row-major wall coordinates for final hanging instructions.

Map bindings use region signatures instead of contiguous map ids. One wall cell may retain multiple equivalent ids, shown as `4/5` in the final hanging order, so duplicate maps of the same region are useful choices rather than conflicts. Empty maps always open as vanilla scale 0 maps. A new id may be stored as a provisional target capture on a slow server, but the route cell is not complete until a matching map state is observed.

## Server compatibility

Inventory operations are conservative and re-check client state before continuing. If an operation cannot be verified, the run pauses and the HUD tells the player what needs attention. Automatic movement is behind an explicit mode, shows an on-screen warning, and can be paused with `U` or emergency-stopped with `K`.

## Automatic walking and aggressive mode

- Route planning is local and incremental: for each target map region, the navigator aims for the nearest reachable point inside the region rather than forcing the player to stand on the map center.
- Path searches run on a dedicated daemon thread. The client thread captures a bounded, loaded-chunk-only terrain snapshot; a completed path is accepted only if its target generation still matches and the player has not drifted too far from the snapshot start.
- Every path step is checked again against the live world before execution. Upward traversal is limited to a normal one-block jump, jumping requires ground or water contact, drops are limited to three blocks, and unloaded or hazardous cells are rejected.
- Normal mode follows camera-facing vanilla input. Aggressive mode keeps a separate server-facing travel direction so the player may free-look or keep an ordinary inventory screen open, while horizontal speed and vertical motion remain bounded.
- Block placement is a fallback with a high path cost and a strict cobblestone/dirt whitelist. In aggressive mode placement is a non-blocking side action: movement may continue cautiously while the controller waits for the server to confirm the new support block, and the accepted path is retained after confirmation.
- Placement and breaking use two phases: after the world change is acknowledged, the controller must safely enter and land in that waypoint before the movement path can advance. World and inventory interactions remain on the client thread; only immutable path search work runs in the background.
- Aggressive mode enables a conservative natural-terrain breaking whitelist by default. Players can disable breaking, edit the ids, or switch between whitelist and blacklist in the navigation settings; the configuration is persisted in `config/mappywall/navigation.json`. Breaking remains the last planning resort with a higher cost than bridging, and fluids, block entities, unbreakable blocks, and dangerous blocks are rejected independently of the user list.
- Missing paths, stale plans, placement acknowledgement failures, collisions, and stalls release movement input and use bounded retries before pausing. They never trigger blind forward movement or unconditional jumping.
- Input, sprint/sneak, boat paddle, and aggressive server-look state are reset on pause, emergency stop, project/world changes, vehicle changes, and other terminal transitions.
- Target and path markers use Minecraft 26.2's level render-state API and render after translucent world features.

## Map opening, zooming, and fill verification

- Before using an empty map, the controller derives the vanilla scale-0 region from the player's current coordinates and projects it to the task scale; item use is allowed only when that result is the target route region. A newly opened id is not bound until a positive MapState agrees. A wrong-region result is ignored and retried with a new empty map.
- A provisional id that later proves to belong to another task region is automatically migrated there as an alias, releasing the originally intended cell so automation can open a replacement. Only contradictory observations for one id, a previously positively verified id being invalidated, corrupt saves, or different jobs persistently assigning the same verified id to different regions enter `CONFLICT`. Cross-job conflicts list the involved job ids and can be resolved by deleting stale jobs from the task screen.
- Both open-first and fill-after-open projects require a positively verified target-scale map id before a cell can complete. This also reopens premature high-scale completions written by older saves when they are loaded.
- Fill-after-open leases the offhand for the exact target-scale task map so temporary main-hand changes for blocks, food, boats, or fireworks cannot interrupt map updates. Completion uses coverage growth/stability observations, conservative minimum coverage thresholds, and bounded repeat passes instead of treating inventory presence as completion.
- Aggressive automatic zoom operates only in an open cartography table. It validates the map input, paper input, post-processing result, and server acknowledgement at every scale step; unrelated table contents are never quick-moved.
- Inventory observations include main inventory and offhand, deduplicate map ids, cache metadata within a tick, and reconcile manual changes with route state before automation resumes.
- Saves are written through a same-directory durable temporary file with atomic replacement when supported. A validated backup is used only to recover a present but corrupt primary file; deleting a project cannot resurrect its backup.
