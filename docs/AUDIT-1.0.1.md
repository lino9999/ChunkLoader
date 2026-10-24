# ChunkLoader 1.0.1 maintenance audit

Completed on 24 October 2026. This audit covers the loader lifecycle, persistence, configuration, item metadata, commands, menus, displays, chunk acquisition and farm simulation. It supplements the initial 1.0.0 verification in [COMPATIBILITY.md](COMPATIBILITY.md).

## Changes verified

- Loader time follows actual ticket activation and each loader's placement time. Reads, refueling, returned items, saves and world unloading use the same accounting.
- Placement protection starts during the event. Canceled placements discard metadata without deleting restored blocks or duplicating items. Off-hand interactions, TNT priming and multi-block placement are handled explicitly.
- Access uses registered ownership. Expired or missing blocks cannot receive paid time. Delayed menu actions verify the current menu and physical block.
- Chunk acquisition has a deduplicated queue, bounded scanning, at most eight unfinished requests and a 200-tick retry delay. Stale world completions cannot overwrite newer requests.
- Storage workers receive immutable snapshots and perform no Bukkit scheduling. Save failures reach the main thread, final-write failures propagate, and shutdown continues cleaning up other components.
- Configuration rejects invalid primitive types, fractional integers and overflow. Malformed item tag types are ignored safely.
- Spawner cancellation also covers non-living entities. Disabled simulations discard backlog; interval changes preserve elapsed time. Active-chunk snapshots and spawn-rule lookups avoid repeated work.

## Automated checks

Clean Maven builds succeeded with Java 21 and Java 25, retaining the Java 21 compilation target. The final focused suite contained 78 checks: 56 earlier checks and 22 additional regression checks. All 78 passed on each JDK, with no skipped or aborted tests.

The additional checks cover activation timing, refueling and paused worlds, ownership snapshots, missing and expired loaders, canceled placements, off-hand interaction, physical block failures, restored expired records, retry timing, stale world callbacks, persistence coalescing and failure propagation, shutdown cleanup, malformed item tags, configuration types, menu charging, simulation interval changes and disabled spawner backlog.

Checks and their dependencies were run from an isolated temporary directory. Test sources, harnesses, logs and disposable servers are removed after verification at the owner's request; the repository retains this report. CI performs compilation and packaging, not this temporary test suite.

## Runtime verification

The final source was exercised on fresh, isolated worlds using official Paper downloads with verified checksums. Every server had no connected players and empty-server pausing disabled.

| Minecraft | Paper build | Java | Functional assertions | Restart assertions |
| --- | --- | --- | --- | --- |
| 1.21.11 | 132 | 21 | 22 passed | 5 passed |
| 26.1.2 | 74 | 25 | 22 passed | 5 passed |
| 26.2 | 134 | 25 | 22 passed | 5 passed |
| 26.3 | 218 | 25 | 22 passed | 5 passed |

All 108 runtime assertions passed across eight boots. The suite checked API registration and lookup, ownership and access extensions, time additions, overlapping tickets, entity-ticking state, expiration, infinite duration, crops, furnace smelting, hopper transfer, custom spawners and cancellation before insertion. Restart checks covered persistence, restored tickets and removal. No ChunkLoader enable, disable, event or simulation failures were recorded in the final runs.

All 26 application class entries in the packaged artifact matched those used for the functional runs. The restart runs used the final release JAR directly.

Final artifact: `ChunkLoader-1.0.1.jar`

SHA-256: `94b7f3a84a468e384ca4a3a7b23fb9ace23e22d5d48d25fb195390f3d988e322`

## Scope and limits

The final source review found no further confirmed defect within the audited scope. These results cover the listed scenarios, not every plugin combination, Minecraft block, mob or production workload. The public-API spawner limitations and event-order differences documented in [COMPATIBILITY.md](COMPATIBILITY.md) still apply. This was a correctness audit, not a production performance benchmark.
