package io.github.lino.chunkloader.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.lino.chunkloader.model.BlockPosition;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

class ChunkTicketsTest {
    @Test
    void overlappingLoadersKeepOneTicketUntilTheLastLoaderIsRemoved() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        World world = mock(World.class);
        Chunk chunk = mock(Chunk.class);
        UUID worldId = UUID.randomUUID();
        BlockPosition first = new BlockPosition(worldId, -1, 60, 31);
        BlockPosition second = new BlockPosition(worldId, -12, -20, 17);
        when(plugin.isEnabled()).thenReturn(true);
        when(world.getChunkAtAsync(-1, 1, true)).thenReturn(CompletableFuture.completedFuture(chunk));
        BukkitScheduler scheduler = immediateScheduler(plugin);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld(worldId)).thenReturn(world);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            ChunkTickets tickets = new ChunkTickets(plugin);
            tickets.retain(first);
            tickets.retain(second);
            tickets.pump(10);
            assertTrue(tickets.active(first));
            assertTrue(tickets.active(second));
            verify(world, times(1)).getChunkAtAsync(-1, 1, true);
            verify(chunk, times(1)).addPluginChunkTicket(plugin);
            tickets.release(first);
            assertTrue(tickets.active(second));
            verify(chunk, never()).removePluginChunkTicket(plugin);
            tickets.release(second);
            tickets.release(second);
            assertFalse(tickets.active(first));
            verify(chunk, times(1)).removePluginChunkTicket(plugin);
        }
    }

    @Test
    void completingAnAsyncLoadAfterRemovalNeverRecreatesItsTicket() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        World world = mock(World.class);
        Chunk chunk = mock(Chunk.class);
        UUID worldId = UUID.randomUUID();
        BlockPosition position = new BlockPosition(worldId, 0, 64, 0);
        CompletableFuture<Chunk> loading = new CompletableFuture<>();
        when(plugin.isEnabled()).thenReturn(true);
        when(world.getChunkAtAsync(0, 0, true)).thenReturn(loading);
        BukkitScheduler scheduler = immediateScheduler(plugin);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld(worldId)).thenReturn(world);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            ChunkTickets tickets = new ChunkTickets(plugin);
            tickets.retain(position);
            tickets.pump(1);
            tickets.release(position);
            loading.complete(chunk);
            assertFalse(tickets.active(position));
            verify(chunk, never()).addPluginChunkTicket(plugin);
        }
    }

    @Test
    void completingAnAsyncLoadAfterShutdownNeverSchedulesOrRecreatesTickets() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        World world = mock(World.class);
        Chunk chunk = mock(Chunk.class);
        UUID worldId = UUID.randomUUID();
        BlockPosition position = new BlockPosition(worldId, 0, 64, 0);
        CompletableFuture<Chunk> loading = new CompletableFuture<>();
        when(plugin.isEnabled()).thenReturn(true);
        when(world.getChunkAtAsync(0, 0, true)).thenReturn(loading);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld(worldId)).thenReturn(world);
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world));
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            ChunkTickets tickets = new ChunkTickets(plugin);
            tickets.retain(position);
            tickets.pump(1);
            tickets.close();
            loading.complete(chunk);
            assertFalse(tickets.active(position));
            verify(chunk, never()).addPluginChunkTicket(plugin);
            verify(scheduler, never()).runTask(eq(plugin), any(Runnable.class));
            verify(world).removePluginChunkTickets(plugin);
        }
    }

    @Test
    void completionFromAnUnloadedWorldCannotAttachToAReplacementWorldWithTheSameId() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        World original = mock(World.class);
        World replacement = mock(World.class);
        Chunk chunk = mock(Chunk.class);
        UUID worldId = UUID.randomUUID();
        BlockPosition position = new BlockPosition(worldId, 0, 64, 0);
        CompletableFuture<Chunk> loading = new CompletableFuture<>();
        when(plugin.isEnabled()).thenReturn(true);
        when(original.getUID()).thenReturn(worldId);
        when(original.getChunkAtAsync(0, 0, true)).thenReturn(loading);
        BukkitScheduler scheduler = immediateScheduler(plugin);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld(worldId)).thenReturn(original);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            ChunkTickets tickets = new ChunkTickets(plugin);
            tickets.retain(position);
            tickets.pump(1);
            tickets.worldUnloaded(original);
            bukkit.when(() -> Bukkit.getWorld(worldId)).thenReturn(replacement);
            loading.complete(chunk);
            assertFalse(tickets.active(position));
            verify(chunk, never()).addPluginChunkTicket(plugin);
        }
    }

    private static BukkitScheduler immediateScheduler(JavaPlugin plugin) {
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        return scheduler;
    }
}
