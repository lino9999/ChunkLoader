<div align="center">

# ChunkLoader

**Keep your Minecraft chunks, machines, crops, and spawners running.**

A lightweight Paper plugin by **Lino**, with timed and infinite chunk loaders, experience and economy refueling, built-in holograms, and offline farm simulation.

[![Build](https://github.com/lino9999/ChunkLoader/actions/workflows/build.yml/badge.svg)](https://github.com/lino9999/ChunkLoader/actions/workflows/build.yml)
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11%E2%80%9326.3-62b47a)
![Paper](https://img.shields.io/badge/Platform-Paper-ffffff)
![Java](https://img.shields.io/badge/Java-21%20%2F%2025-orange)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

[Installation](#installation) · [Commands](#commands) · [Permissions](#permissions) · [Configuration](#configuration) · [Development](#development)

</div>

## Features

- Keep one complete chunk loaded per placed loader, including entities, redstone, hoppers, and furnaces.
- Give players temporary loaders or permanent loaders with unlimited time.
- Add time using another loader item, XP levels, or an optional Vault economy provider.
- Open a management menu with right-click and a removal confirmation with left-click.
- Show the remaining time above each loader using native text displays, with no hologram dependency.
- Continue random block ticking and inactive mob spawner operation without nearby players.
- Protect loaders from explosions, pistons, fire, fluid replacement, and entity changes.
- Enforce ownership, configurable placement limits, and world restrictions.
- Display personal chunk borders with `/chunkloader show` and find loaders with a paginated list.
- Retain remaining time and ownership across restarts using validated JSON storage and atomic backups.
- Share chunk tickets correctly when several loaders occupy the same chunk.

## Installation

1. Use Paper **1.21.11 through 26.3**. Run Java **21** for 1.21.11 and Java **25** for 26.x.
2. Download a built `ChunkLoader-1.0.0.jar` from the [build artifacts](https://github.com/lino9999/ChunkLoader/actions/workflows/build.yml), or build it locally.
3. Put the JAR in your server's `plugins` directory and restart the server.
4. Optionally install [Vault](https://github.com/MilkBowl/Vault) and an economy plugin for money refueling.
5. Give yourself a loader: `/chunkloader give YourName 2h`.

Spigot, Bukkit-only servers, Fabric, Forge, Bedrock servers, and Folia are outside the supported platform. See [compatibility and testing](docs/COMPATIBILITY.md) for the tested builds and scope.

## Using a loader

Place the supplied block to load its chunk. The default block is an end portal frame; ordinary, untagged blocks do not become loaders.

Right-click with an empty hand to open the menu. Buy an additional **30 minutes** for **10 XP levels** or **5000 currency units** by default. Right-click while holding another loader to merge its remaining time. Merging an infinite item makes the placed loader infinite. Creative players can refuel without consuming items or paying.

Left-click to request removal and confirm in the menu. With `loader.allow-breaking: true`, breaking a loader also removes it and returns an item carrying the remaining time. With that setting disabled, confirmed removal destroys the loader without returning an item. Expired loaders disappear automatically and never drop a replacement.

Each loader covers **one chunk**. Overlapping loaders have separate timers and share one chunk ticket. Time follows server ticks and pauses while the server or the loader's world is offline; lag slows the timer. A loader waiting for its chunk to activate does not consume time. Loader items can be traded; the player who places one becomes its owner.

## Commands

Use `/chunkloader`, `/cloader`, or `/cl`. Commands and permission nodes use the new ChunkLoader names.

| Command | Description | Permission |
| --- | --- | --- |
| `/chunkloader help` | Show command help. | Everyone |
| `/chunkloader show` | Outline nearby chunks kept active by loaders; particles are visible only to you. | `chunkloader.show` |
| `/chunkloader list [page]` | List your loaders, locations, IDs, and remaining time; admins see all loaders. | `chunkloader.list` |
| `/chunkloader give <player\|all\|@a> <duration> [amount]` | Give 1–64 loader items per online recipient. Inventory overflow drops at the recipient's feet. | `chunkloader.give` |
| `/chunkloader reload` | Validate and reload configuration and messages; invalid changes leave the active settings intact. | `chunkloader.reload` |
| `/chunkloader settings` | Open the administrator menu to toggle display, fuel, breaking, and simulation options. | `chunkloader.settings` |

Duration formats: `90s`, `30m`, `2h`, `1d`, `inf`, or `infinite`. A positive number without a suffix means **minutes**. Use a single unit per duration.

```text
/chunkloader give Steve 30m
/cl give Alex 2h 4
/cloader give all infinite
/chunkloader list 2
```

## Permissions

| Permission | Default | Access |
| --- | --- | --- |
| `chunkloader.use` | Everyone | Place, manage, merge fuel into, and remove your own loaders. |
| `chunkloader.show` | Everyone | Toggle chunk borders. |
| `chunkloader.list` | Everyone | List your own loaders. |
| `chunkloader.give` | Operators | Give loader items. |
| `chunkloader.reload` | Operators | Reload files. |
| `chunkloader.settings` | Operators | Change settings through the GUI. |
| `chunkloader.bypass` | Operators | Access other players' loaders and bypass the per-player placement limit. |
| `chunkloader.admin` | Operators | Includes all permissions above; lists all loaders. |

The global loader cap and disabled-world list also apply to administrators. Protection plugins can cancel placement, interaction, and the block-break event emitted before confirmed removal. Plugins may grant additional shared access through the [public API](docs/ARCHITECTURE.md#public-api).

## Configuration

Files live under `plugins/ChunkLoader/`. Edit [`config.yml`](src/main/resources/config.yml) and [`messages.yml`](src/main/resources/messages.yml), then run `/chunkloader reload`. All bundled messages are English and support MiniMessage formatting. Player names and other substitutions are inserted as plain text.

| Setting | Default | Meaning |
| --- | --- | --- |
| `loader.material` | `END_PORTAL_FRAME` | Block used for newly issued items. Existing loaders retain their material. |
| `loader.max-per-player` | `16` | Placement limit per owner. |
| `loader.max-total` | `2048` | Global placement limit. |
| `loader.allow-breaking` | `false` | Return remaining-time items on manual removal. |
| `loader.disabled-worlds` | `[]` | World names where new loaders cannot be placed. |
| `fuel.duration` | `30m` | Time added by one XP or economy purchase. |
| `fuel.xp.enabled` / `fuel.xp.levels` | `true` / `10` | XP purchase availability and level cost. |
| `fuel.economy.enabled` / `fuel.economy.cost` | `true` / `5000.0` | Optional Vault purchase availability and price. |
| `display.holograms` / `display.particles` | `true` / `true` | Floating text and removal effects. |
| `display.borders-interval-ticks` | `10` | Personal border refresh interval. |
| `simulation.random-ticks` / `simulation.spawners` | `true` / `true` | Supplemental offline farm simulation. |
| `simulation.interval-ticks` | `3` | Ticks between simulation passes. |
| `simulation.max-chunks-per-tick` | `8` | Maximum chunks processed per simulation pass. |
| `simulation.max-random-samples-per-chunk` | `2048` | Maximum random block samples per visited chunk. |
| `storage.autosave-seconds` | `30` | Periodic countdown checkpoint interval. |

Simulation is bounded and visits chunks in rotation. When the configured work budget is exhausted, supplemental farm throughput can fall below vanilla speed. Paper's entity-ticking tickets already provide native crop and fluid random ticks, so ChunkLoader avoids adding duplicate ticks. A bounded fallback uses native block callbacks only where native ticking is unavailable and no simulating player is nearby. The public-API spawner engine respects delays, caps, spawn snapshots, weighted potentials, custom light rules, collision, difficulty, and cancellable spawn events. Species-specific native spawning predicates and equipment-table selection can differ from vanilla. Natural mob spawning and player-dependent farms retain Minecraft's own requirements. Border rendering covers at most 16 nearby loader chunks within an eight-chunk radius and never loads extra chunks.

Minecraft can pause an empty server. Set `pause-when-empty-seconds=-1` in `server.properties` if you want loaders to continue running while nobody is connected.

## Storage and upgrades

`loaders.json` stores UUIDs, block positions, remaining ticks, and the material of each placed loader. Every write uses a temporary file and an atomic replacement; `loaders.json.bak` keeps the previous validated snapshot. A malformed store prevents startup and is never silently replaced. Stop the server before restoring a backup.

Placement, refueling, and removal queue an immediate background save; countdown updates are checkpointed periodically. Graceful shutdown waits for the final save. An abrupt process or machine failure can lose changes not yet committed to disk. Back up the plugin data folder together with the worlds.

This is a fresh implementation. Old plugin JARs, database formats, legacy item tags, commands, and permissions are not imported automatically. Remove the old plugin before enabling ChunkLoader; issue fresh loader items and recreate existing placements.

## Development

```sh
./mvnw verify
```

On Windows, use `mvnw.cmd verify`. Set `JAVA_HOME` to a JDK 21 or 25 installation. The same Java 21 bytecode runs across the supported server versions. The production JAR is written to `target/ChunkLoader-1.0.0.jar`; the separate integration test plugin must never be installed on a production server.

Start with the [architecture](docs/ARCHITECTURE.md), [AI editing guide](docs/AGENTS.md), and [reverse-engineering evidence](docs/REVERSE_ENGINEERING.md). Generated files, local tools, original artifacts, and test worlds are excluded from Git and AI searches.

## License

ChunkLoader is released under the [MIT License](LICENSE). See [third-party notices](THIRD_PARTY_NOTICES.md) for bundled and development dependencies.
