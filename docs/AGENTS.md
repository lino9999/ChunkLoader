# Working on ChunkLoader

Read `docs/ARCHITECTURE.md`, choose files from its concern table and open only relevant paths. Prefer targeted `rg` under `src/` or `docs/`. Do not scan `.git/`, `.work/`, `target/`, `Plugin originale/`, dependencies or generated binaries. Read an exact analysis artifact only when explicitly needed.

## Constraints

- Keep application code, messages and documentation in English. Plugin author is Lino.
- Do not add source comments. Use clear names, small cohesive methods and documentation under `docs/`.
- Compile for Java 21 and use public Paper APIs; avoid version-specific native classes and reflection-based adapters.
- Keep mutable state and Bukkit operations on the main server thread. Background work receives immutable snapshots only.
- Preserve unique loader IDs/positions, checked duration arithmetic and the `-1` infinity sentinel.
- Preserve chunk reference counts, idempotent removal, item metadata validation and access/liveness checks before paid actions.
- Never overwrite corrupt persistence, silently discard failed writes or replace verified backup data with unvalidated input.
- Keep simulation work bounded; skip supplemental random ticks in `ENTITY_TICKING` chunks and near simulating players. Describe public-API spawner limits honestly.
- Keep command declarations, aliases, permissions, messages and README examples consistent.

## Verify efficiently

Run `./mvnw -B verify` on Unix or `.\mvnw.cmd -B verify` on Windows with a JDK installed. Use `-Dtest=ClassName` for focused iteration, then run the full required check after related changes. Tests belong under matching packages in `src/test/java/`. Add tests for meaningful lifecycle, arithmetic, persistence or compatibility failures; do not mirror trivial implementation details.

CI checks Java 21 and 25. Runtime integration establishes server behavior; a build alone cannot prove Minecraft compatibility. Do not commit original/decompiled code, downloads, worlds, logs or build artifacts. Update architecture documentation when module boundaries or invariants change.
