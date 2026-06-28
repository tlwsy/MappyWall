# MappyWall Design Notes

MappyWall is a client-only Fabric mod for planning and opening map walls on vanilla-compatible servers.

Current implementation work targets Minecraft `1.21.11` with Yarn mappings while Minecraft `26.1.2` mappings mature.

## Current boundaries

- Manual routing is available and remains the safest/default mode.
- Automatic walking is available as an explicit mode. It uses only client-side vanilla inputs and interactions, including sprinting, jumping, conservative local path planning, allowed block breaking/placing, eating, and optional boat use when water is encountered.
- Elytra/firework automation is still outside the current implementation.
- The core planner has no Minecraft dependencies so route math, binding recovery, and persistence can be tested without launching the game.
- Client integration must not register blocks, items, entities, server packets, or any behavior that assumes a Fabric server.

## Map wall model

The first planned map is anchored to the map region containing the player when the project is created. Wall columns move east (`+X`) and rows move south (`+Z`). The MVP route uses a snake pattern to reduce travel distance while preserving row-major wall coordinates for final hanging instructions.

Map bindings use region signatures instead of contiguous map ids. Empty maps always open as vanilla scale 0 maps; MappyWall binds the current wall cell at open time and can later repair manual openings from observed map state when there is a unique region match.

## Server compatibility

Inventory operations are conservative and re-check client state before continuing. If an operation cannot be verified, the run pauses and the HUD tells the player what needs attention. Automatic movement is behind an explicit mode, shows an on-screen warning, and can be paused with `U` or emergency-stopped with `K`.

## Stage 4 automatic walking

- Route planning is local and incremental: for each target map region, the navigator aims for the nearest reachable point inside the region rather than forcing the player to stand on the map center.
- Path searches run on a dedicated daemon thread. The client thread only captures a bounded terrain snapshot and later applies completed path results, so expensive A* work does not block rendering or normal game ticks.
- The controller sprints by default, follows the local path, and replans periodically or when progress stalls.
- Stuck recovery no longer immediately pauses the task. It turns toward the local target, jumps, attempts to break an allowed obstacle directly ahead, and keeps replanning.
- The path planner supports walking, one-block jumps, controlled drops, swimming/water traversal, allowed block breaking, and allowed block placement using the default cobblestone/dirt whitelist.
- Target and path markers render through terrain so the player can see the current target direction even when it is behind blocks.
