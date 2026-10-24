# Reconstruction evidence

ChunkLoader is an independent rewrite based on the behavior recovered from the supplied `epicanchors-4.8.0.jar`. Decompiled implementation files and the original binary are local analysis inputs and are excluded from the repository.

## Artifact identity and retained evidence

| Property | Value |
| --- | --- |
| Input | `Plugin originale/epicanchors-4.8.0.jar` |
| Size | 3,176,730 bytes |
| SHA-256 | `1a9bb16afad19bc0c9eaf8e5ce6b13dcf1374a15a423ebbf4abe5172b10117f5` |
| REA provider | Safe artifact graph provider, version 1 |
| Inventory | 2,165 occurrences: 1,741 files and 424 directories |
| Retained ledger | `.work/analysis/rea-evidence.json`, excluded from Git |

| Evidence | Retained identifier | Scope |
| --- | --- | --- |
| Artifact inspection | `ev_c072cc4bc430295619f78a2187d86bbfd5504ed00523bfc3807dfec91b8c55c9` | Supplied ZIP/JAR structure |
| Complete entry inventory | `ev_97344c6e4de33f1bc6214c85bb56672006d49916421cea54c35365819da93907` | Central-directory entries and verified entry hashes |
| Inspection summary | `ev_8676a8b1a479970846606f00e6f25291e2ad1defe270504dff468dcd3cac594a` | Derived summary of the two records above |

The inventory establishes packaged contents, including `plugin.yml`, `database.yml`, native version adapters and database migrations. It does not establish runtime behavior. The following specification comes from focused static inspection of decompiled application classes; it is separate from the REA inventory evidence.

## Recovered behavior

| Area | Static behavior | Main inspected classes |
| --- | --- | --- |
| Loader items | Configured block material with tagged duration and display name; finite durations use server ticks and `-1` means infinite | `AnchorListener`, `AnchorImpl` |
| Placement | Places one loader at a block position and assigns the placing player as owner; several positions can occupy the same chunk | `AnchorListener`, `AnchorManagerImpl` |
| Loading | Keeps each loader's own chunk active; shared chunk work is deduplicated | `AnchorTask`, `AnchorManagerImpl` |
| Offline farms | Every three ticks, inactive spawners receive three native ticks; random ticks sample `randomTickSpeed × 3` positions per eligible ticking section | `AnchorTask`, `WorldCore`, version-specific `WorldCoreImpl` |
| Interaction | Owner, administrator or registered API access predicate may interact; a held loader item adds its remaining duration and consumes one item outside creative mode | `AnchorListener` |
| Management menu | A 27-slot inventory shows remaining time; default upgrades cost 10 XP levels or 5,000 economy units and add 30 minutes | `AnchorGui` and GUI handlers |
| Infinite duration | Never expires and refuses paid duration upgrades | `AnchorImpl` and GUI handlers |
| Removal | Confirmation precedes removal; configurable drops preserve remaining time; expiry removes the loader without a drop | `AnchorListener`, `AnchorManagerImpl` |
| Display | Optional one-line name/time holograms; optional nearby chunk-border particles | Hologram implementation, visualizer task |
| Administration | Give an exact online player or all online players an item, show chunks, reload configuration and open configuration settings | Command implementations |
| Persistence | H2 entries store world name, block coordinates, remaining ticks and owner; saves run periodically, on world unload and on disable | Database manager, migrations, save task |
| API | Create, find and destroy loaders, change duration and register interaction access predicates | Public manager interfaces |

Countdown is based on active server ticks and pauses while the server is offline or the world is unloaded. The original's native extension checks player presence in the exact chunk and stops after an incompatible native implementation fails. A bounded inspection of the application classes found no crafting recipe, fuel inventory, configurable loader radius or player trust-management interface; these absences are limited to the inspected artifact.

ChunkLoader replaces the old command and permission namespace, item tags, ownership identity and persistence format. Its public commands, configuration and API are documented in the root README and architecture guide.

## Defects identified for the rewrite

These are static observations and inferred failure modes, not reproduced runtime incidents.

| Original call path | Observation and inferred consequence | Rewrite requirement |
| --- | --- | --- |
| `AnchorListener:116`, `AnchorImpl:103–107` | Infinite merge supplies `-1` to a setter that rejects negative values | Represent infinity consistently across all duration operations |
| `GiveCommand:45,53,57`, `AnchorImpl:113` | Integer arithmetic lacks overflow checks; inventory insertion leftovers are ignored | Validate arithmetic and handle full inventories |
| `AnchorManagerImpl:212–235,243–264`, `AnchorTask:89–94`, `WorldUtils:50–61` | Ticket initialization depends on chunk loaded state; destroying one loader deinitializes shared chunk resources | Maintain explicit per-chunk reference counts and owned tickets |
| `AnchorGui:73–105`, `AnchorManagerImpl:243–264` | Removal has no liveness guard; stale menus can drop twice or charge after removal | Make removal idempotent and recheck access and existence at mutation time |
| `AnchorManagerImpl:216–230` and startup map access | Delayed completion can replace a newer location entry; map access crosses threads | Serialize state changes and use immutable persistence snapshots |
| `_1_InitialMigration:21`, database deletion path | No unique block-position constraint; failed asynchronous deletion can leave persisted entries | Enforce unique IDs and positions and surface storage failures |
| `AnchorTask:51–54` | An unavailable world returns from the entire update task | Isolate unavailable worlds and continue processing others |
| `AnchorTask:65–67` | Exact-chunk player checks can overlap naturally ticking neighboring chunks | Gate native work using actual natural ticking eligibility |
| `BlockListener:46–63` and event-handler inventory | Piston checks inspect the base instead of moved blocks; several destruction paths lack coverage | Protect loader positions across movement and destructive events |
| `VisualizeTask:69,80`, hologram update path | Visualization loads unrelated chunks, skips negative heights and retains players; displays update even when text is unchanged | Avoid visualization-driven chunk loads and update displays only as needed |
| Item recognition | Legacy name-prefix recognition accepts untagged lookalikes | Authenticate loader items using namespaced persistent data |
| Periodic countdown saves | A crash can roll duration back to the last successful periodic save | Document checkpoint behavior and persist important state transitions |

## Limits and verification

Static decompilation can lose source-level intent and cannot prove that every observed branch executes on every server version. Archive inventory excludes non-entry ZIP bytes and does not establish network behavior, runtime safety, compatibility or absence of additional defects. No original source code is incorporated into the new application implementation.

The rewrite uses public Paper APIs rather than the original native adapters. Its random-tick fallback explicitly skips `ENTITY_TICKING` chunks, where the server already ticks blocks and fluids, and also skips chunks near simulating players. Inactive spawners use bounded custom simulation with public spawn rules and events; this does not establish full native spawn-predicate parity. Borders inspect only nearby actively ticketed loader chunks. API creation validates the material, loaded world/chunk and coordinate bounds before registration.

At the reconstruction checkpoint, 56 unit tests passed across domain, persistence, commands, tickets and simulation gating. These checks are distinct from server integration evidence and do not reproduce or validate every original behavior.

Compatibility and integration claims must come from the new plugin's build checks and explicit server tests. Native Minecraft behavior requires separate verification for each supported runtime; a successful Java compilation alone does not validate spawners, random ticks, chunk tickets, graphical interactions or future Minecraft releases.
