# Architecture

ChunkLoader targets the public Paper API, compiles to Java 21 bytecode and uses immutable domain records. Start with the row relevant to a change; source paths below are relative to `src/main/java/io/github/lino/chunkloader/`.

| Concern | Files |
| --- | --- |
| Startup, scheduler, shutdown and service registration | `ChunkLoaderPlugin.java` |
| Loader lifecycle, indexes, access checks and save scheduling | `service/LoaderService.java` |
| Identity, coordinates, infinity and duration arithmetic | `model/BlockPosition.java`, `model/Loader.java`, `model/Durations.java` |
| JSON schema, validation, backup and atomic replacement | `storage/LoaderStore.java` |
| Shared chunk tickets and asynchronous chunk acquisition | `runtime/ChunkTickets.java` |
| Offline random ticks and spawner simulation | `runtime/FarmSimulation.java` |
| Holograms and personal chunk borders | `runtime/LoaderDisplays.java` |
| Item metadata and duration recognition | `item/LoaderItems.java` |
| Placement, interaction and world/block protection | `listener/LoaderListener.java` |
| Commands and inventory menus | `command/LoaderCommand.java`, `ui/LoaderMenus.java` |
| Validated settings and English messages | `config/Settings.java`, `config/Messages.java` |
| Optional Vault economy adapter | `integration/EconomyBridge.java` |
| Public integration contract | `api/ChunkLoaderApi.java` |

## State and threading

`LoaderService` owns the live loader map and its position/owner indexes. Every public API call must run on the main server thread. Bukkit objects, menus, entity operations and chunk-ticket changes also stay on that thread. The storage executor receives only immutable `List<Loader>` snapshots and performs file I/O in order; it never accesses Bukkit state.

One loader ID identifies one block position in one world UUID. IDs and full positions are unique, while several loaders may share a chunk. Loader records are replaced rather than mutated. Remaining ticks are nonnegative, with exactly `-1` representing infinity; expiry is zero. Addition uses checked arithmetic. The service tracks each loader's last accounted tick and each chunk's activation tick. Reads, refueling, drops and save snapshots report actual active time; the periodic sweep commits countdowns and expires blocks every 20 ticks. Unloading a world settles its time before releasing tickets. Offline servers and unavailable worlds do not consume time.

Chunk tickets use a reference count per world/chunk coordinate. Retaining the first loader queues acquisition; releasing the last removes only this plugin's ticket. A deduplicated queue bounds both scanning and acquisition, with at most eight unfinished requests. Failed loads retry after 200 ticks. Request identity prevents old-world callbacks from clearing a newer request. Completion runs on the main thread; already synchronous completions do not wait another tick. The immutable active-chunk snapshot changes only when membership changes. Removing one loader must preserve tickets needed by other loaders. World load/unload and plugin shutdown release or reacquire owned resources.

Placement registers metadata during the placement event so the block is protected immediately. The monitor handler discards canceled placement metadata without touching the block state restored by the server. Items that place multiple blocks are rejected. Off-hand interactions and TNT priming are protected as well. Removal confirmation rechecks access after external block-break handlers run.

## Persistence

The data file is `plugins/ChunkLoader/loaders.json`. Schema version 1 contains `schemaVersion` and a `loaders` array. Each entry stores `id`, `ownerId`, `position` (`worldId`, `x`, `y`, `z`), `remainingTicks` and `material`.

`LoaderStore` rejects malformed UTF-8/JSON, unsupported schemas, invalid records, duplicate identities/positions and excessive input. It writes a forced temporary file and atomically replaces the destination. Before replacement, an existing valid document becomes `loaders.json.bak`. Corrupt primary data causes startup/save failure and is never silently overwritten or restored from backup.

Lifecycle mutations request saves immediately; countdown changes are checkpointed at the configured autosave interval. One writer coalesces concurrent save requests and shutdown queues a final snapshot after earlier writes. Worker completion is polled on the main thread rather than scheduling Bukkit callbacks from the worker, so shutdown can drain storage without scheduling against a disabled plugin. A process or machine crash can lose changes after the last completed checkpoint. Failed writes are logged and mark storage unhealthy; creation and time additions are blocked until a successful write. Final-save errors propagate to shutdown, and cleanup of other components continues if one fails.

## Settings and presentation

Defaults live in `src/main/resources/config.yml`; `Settings.read` validates them before publishing a new settings record. Groups cover loader material/limits/world exclusions, XP/economy duration upgrades, displays, simulation budgets and autosave timing. Reload validates candidate settings and messages before replacing the active values. `messages.yml` contains player-facing English text, and `plugin.yml` is the command/permission contract. Loader item identity uses namespaced persistent data with an explicit item version, not display-name matching.

Personal borders show the nearest 16 actively ticketed loader chunks within eight chunks on each horizontal axis in the player's world. Drawing uses existing ticketed chunks, respects the world's vertical bounds and never loads unrelated chunks.

## Simulation boundaries

Farm work uses public Paper APIs and bounded round-robin budgets. Paper chunks at `ENTITY_TICKING` already receive natural block and fluid random ticks; supplemental random ticking is skipped to prevent duplicate growth. Fallback sampling runs only in non-entity-ticking chunks without a nearby simulating player, samples world-height sections and invokes `Block.randomTick()` only for eligible block data. Spawner simulation advances inactive spawners, respects delay/count/nearby limits, weighted potential entries, available custom light rules, collision checks and cancellable spawn events.

This is a custom public-API simulation, not a complete reproduction of every native spawn predicate, fluid tick or entity-specific Minecraft rule. Native parity must not be claimed from compilation or unit tests. Verify representative farms and custom spawners on each supported server runtime before expanding compatibility claims.

## Public API and checks

The plugin registers `ChunkLoaderApi` through Bukkit's services manager. The API exposes immutable snapshots, identity/position lookup, creation, time additions, idempotent removal and access predicates. Creation requires a solid placeable block material, a loaded world/chunk, coordinates within world bounds, and a matching block at that position. Fuel additions require a live physical block and an unexpired loader. Access checks use the registered record instead of trusting fields in caller-supplied snapshots. Callers must use the main thread and apply their own integration policy. Close access registrations on the main thread when an integration unloads.

Run `./mvnw -B package` on Unix or `.\mvnw.cmd -B package` on Windows. CI builds on Java 21 and 25 while retaining the Java 21 compilation target. The initial checks are recorded in [COMPATIBILITY.md](COMPATIBILITY.md), with the later maintenance checks in [AUDIT-1.0.1.md](AUDIT-1.0.1.md). Test sources, dependencies, tooling, and disposable servers are removed after verification at the owner's request. A successful build is a compilation check, not a fresh execution of those historical tests. Original-artifact findings and their limits are recorded in [REVERSE_ENGINEERING.md](REVERSE_ENGINEERING.md).
