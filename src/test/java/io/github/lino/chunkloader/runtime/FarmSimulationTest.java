package io.github.lino.chunkloader.runtime;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

class FarmSimulationTest {
    @Test
    void nativeEntityTickingDoesNotReceiveAdditionalRandomTicks() {
        Chunk chunk = chunk();
        when(chunk.getLoadLevel()).thenReturn(Chunk.LoadLevel.ENTITY_TICKING);
        FarmSimulation simulation = new FarmSimulation(mock(JavaPlugin.class));
        simulation.tick(List.of(chunk, chunk), 3, true, false, 2048, 8);
        verify(chunk, never()).getBlock(anyInt(), anyInt(), anyInt());
    }

    @Test
    void activeSpawnerIsLeftToNativeSimulation() {
        Chunk chunk = chunk();
        CreatureSpawner spawner = mock(CreatureSpawner.class);
        when(spawner.isActivated()).thenReturn(true);
        when(chunk.getTileEntities(false)).thenReturn(new BlockState[] {spawner});
        FarmSimulation simulation = new FarmSimulation(mock(JavaPlugin.class));
        simulation.tick(List.of(chunk), 3, false, true, 2048, 8);
        verify(spawner, never()).setDelay(anyInt());
        verify(spawner, never()).update();
    }

    @Test
    void duplicateChunkReferencesDoNotAccelerateInactiveSpawners() {
        Chunk chunk = chunk();
        CreatureSpawner spawner = mock(CreatureSpawner.class);
        when(spawner.getSpawnCount()).thenReturn(1);
        when(spawner.getMaxNearbyEntities()).thenReturn(6);
        when(spawner.getDelay()).thenReturn(20);
        when(chunk.getTileEntities(false)).thenReturn(new BlockState[] {spawner});
        FarmSimulation simulation = new FarmSimulation(mock(JavaPlugin.class));
        simulation.tick(List.of(chunk, chunk), 3, false, true, 2048, 8);
        verify(spawner).setDelay(17);
        verify(spawner).update();
    }

    private Chunk chunk() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        Chunk chunk = mock(Chunk.class);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.isLoaded()).thenReturn(true);
        return chunk;
    }
}
