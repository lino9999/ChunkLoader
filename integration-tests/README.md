# Runtime integration tests

This separate plugin validates ChunkLoader inside real Paper servers. It creates and changes blocks and entities, persists a loader across two server boots, and automatically shuts down each server. Install it only on disposable test servers.

From PowerShell on Windows, supply Java 21 and Java 25 installation directories. After reading and accepting the [Minecraft EULA](https://aka.ms/MinecraftEULA), run:

```powershell
.\integration-tests\run.ps1 -Java21Home 'C:\Java\jdk-21' -Java25Home 'C:\Java\jdk-25' -AcceptEula
```

The script builds and tests the main project, builds the integration plugin, downloads official stable Paper builds, verifies their SHA-256 checksums, and runs the matrix sequentially. Every invocation creates a new directory under `.work/integration/`; existing worlds are never overwritten or deleted. Servers bind to localhost and use ports 25600–25603. A complete run needs several minutes, network access and approximately 1 GB of server heap plus build memory.

The matrix covers Paper 1.21.11, 26.1.2, 26.2 and 26.3. Checks include API registration, position lookup, owner and extension access, adding fuel, overlapping tickets, entity-ticking state, expiration, infinite duration, crops, furnaces, hoppers and custom spawner entity snapshots without connected players. It also checks spawner-event cancellation, entity location before insertion and the `SPAWNER` creature-spawn reason. The second boot verifies stored loader and ticket restoration, then removal.

`integration-stage1.json` and `integration-result.json` contain assertions for each server. A control crop plot held by an ordinary plugin ticket records whether the server already performs native random ticks. `summary.json` collects all results. Build metadata and complete server logs remain beside the test worlds.

To build the integration plugin separately:

```powershell
.\mvnw.cmd install
.\mvnw.cmd -f integration-tests/pom.xml package
```

Copy `target/ChunkLoader-1.0.0.jar` and `integration-tests/target/ChunkLoaderIntegration.jar` into a disposable Paper server's `plugins/` directory. Set `pause-when-empty-seconds=-1` so the no-player test keeps ticking. Start twice; the plugin stops each boot automatically. Remove the integration plugin after testing.
