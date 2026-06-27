# MappyWall Design Notes

MappyWall is a client-only Fabric mod for planning and opening map walls on vanilla-compatible servers.

## MVP boundaries

- Only manual routing is active in `0.1.0`.
- Automatic walking and Elytra modes are represented in the data model but remain disabled in the UI.
- The core planner has no Minecraft dependencies so route math, binding recovery, and persistence can be tested without launching the game.
- Client integration must not register blocks, items, entities, server packets, or any behavior that assumes a Fabric server.

## Map wall model

The first planned map is anchored to the map region containing the player when the project is created. Wall columns move east (`+X`) and rows move south (`+Z`). The MVP route uses a snake pattern to reduce travel distance while preserving row-major wall coordinates for final hanging instructions.

Map bindings use region signatures instead of contiguous map ids. A binding is valid only when the observed map state matches the intended dimension, scale, and center coordinates.

## Server compatibility

Inventory operations are conservative and re-check client state before continuing. If an operation cannot be verified, the run pauses and the HUD tells the player what needs attention. Automatic movement is behind an explicit mode and remains off until a later milestone.

