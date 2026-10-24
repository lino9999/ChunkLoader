# Compatibility and verification

ChunkLoader targets the public Paper 1.21.11 API and Java 21 bytecode. The same plugin JAR runs on Paper 1.21.11 through 26.3. Minecraft 26.x servers require Java 25; this does not require a separate plugin JAR. Spigot and Folia are not supported targets.

## Runtime matrix

The isolated runtime suite was executed on 24 October 2026 using official Paper downloads with verified SHA-256 checksums.

| Minecraft | Paper build | Java runtime | Farm and API checks | Restart checks |
| --- | --- | --- | --- | --- |
| 1.21.11 | 132 stable | Microsoft OpenJDK 21.0.7+6-LTS | Pass: 22 assertions | Pass: 5 assertions |
| 26.1.2 | 74 stable | Microsoft OpenJDK 25.0.1+8-LTS | Pass: 22 assertions | Pass: 5 assertions |
| 26.2 | 134 stable | Microsoft OpenJDK 25.0.1+8-LTS | Pass: 22 assertions | Pass: 5 assertions |
| 26.3 | 218 stable | Microsoft OpenJDK 25.0.1+8-LTS | Pass: 22 assertions | Pass: 5 assertions |

All 108 runtime assertions passed across the eight boots, and the main project passed 56 unit tests before the owner requested removal of test files. No ChunkLoader event or farm-simulation errors were recorded in the final run. The initially verified JAR had SHA-256 `5f961a5cf50142b9469a904a1ac83afb6ce433930af732dc34e89b791f5b03e7`. A later release rebuild can have a different archive hash because packaging timestamps change; its application code is unchanged.

The suite checks the registered API, position lookup, ownership, extension access and unregistration, fuel addition, overlapping ticket references, entity-ticking state, expiration and infinite duration. With no connected players, it verifies wheat growth, furnace smelting, hopper transfer and custom spawner entity snapshots. It cancels initial spawner attempts and verifies that cancellation prevents world insertion, event listeners receive the correct entity position, and creature spawn events carry the `SPAWNER` reason. A second boot verifies persisted infinite-loader restoration, ticket recreation and removal.

The worlds are isolated flat test worlds with empty-server pausing disabled. This is a functional compatibility suite, not a production performance benchmark or a test of every Minecraft block and mob. Paper 26.1.1 alpha is not part of the tested stable matrix.

## Native random ticks

Paper plugin chunk tickets promote chunks to entity-ticking status. All four tested servers grew the control crop plot with an ordinary plugin ticket and no connected players. Native random ticking therefore already covers crops and randomly ticked fluids. ChunkLoader skips supplemental random ticks for these chunks, avoiding accelerated growth caused by ticking them twice.

The public-API fallback uses bounded random position samples across chunk sections only when native entity ticking is unavailable. Nearby player simulation also suppresses this fallback. Work is distributed between chunks and sections, with capped backlog to prevent catch-up bursts. Setting `simulation.random-ticks` to false disables the fallback; it does not override native Paper ticking.

## Spawner behavior and boundaries

Ordinary Minecraft spawners still require a nearby player even in entity-ticking chunks. Setting their public required-player range to zero only removes proximity while players are online, so ChunkLoader instead simulates inactive standard spawners through public APIs. It leaves naturally activated spawners to the server.

The simulation preserves current entity snapshots, weighted spawn potentials, delay ranges, spawn counts, nearby entity caps, custom light rules, collision checks and cancellable events. Attempt budgets prevent extreme configured spawn counts or ranges from producing unbounded work.

Public APIs do not expose every native species-specific spawn predicate, spawn-potential equipment-table operation or a direct spawner tick method. Those details are approximated; complex customized spawners can differ from native behavior. Trial spawners, vault player interaction and natural mob spawning retain their native player requirements. Other plugins that change spawn behavior should be tested together with ChunkLoader.

To provide a correctly positioned unspawned snapshot to spawner listeners, the simulated `SpawnerSpawnEvent` is dispatched during the earliest creature-spawn event phase. Its cancellation cancels the creature event before insertion. This public-API event nesting differs from native event dispatch ordering; other plugins depending on exact nested event order should be integration-tested.

## Build verification

Test sources, runtime test tooling, generated reports, and disposable local servers were removed after the initial verification at the owner's request. The matrix above records completed checks; those checks are not rerun by the current build. Use `./mvnw package` or `.\mvnw.cmd package` to compile and package the plugin. CI performs this build on Java 21 and 25. The Git history retains the original verification tooling.

Official references: [Paper Java requirements](https://docs.papermc.io/paper/getting-started/), [Paper API setup](https://docs.papermc.io/paper/dev/project-setup/), [plugin API version behavior](https://docs.papermc.io/paper/dev/plugin-yml/), [chunk tickets](https://jd.papermc.io/paper/1.21.11/org/bukkit/Chunk.html), [block random ticks](https://jd.papermc.io/paper/1.21.11/org/bukkit/block/Block.html), [spawner player range](https://jd.papermc.io/paper/1.21.11/org/bukkit/spawner/BaseSpawner.html), [official download service](https://docs.papermc.io/misc/downloads-service/).
