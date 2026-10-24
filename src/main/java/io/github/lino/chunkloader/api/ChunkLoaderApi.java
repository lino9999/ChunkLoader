package io.github.lino.chunkloader.api;

import io.github.lino.chunkloader.model.BlockPosition;
import io.github.lino.chunkloader.model.Loader;
import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;

public interface ChunkLoaderApi {
    List<Loader> loaders();
    Loader find(BlockPosition position);
    Loader get(UUID id);
    Loader create(UUID owner, BlockPosition position, long ticks, String material);
    Loader addTime(UUID id, long ticks);
    boolean remove(UUID id, boolean dropItem);
    boolean hasAccess(Loader loader, UUID player);
    AutoCloseable registerAccessCheck(BiPredicate<Loader, UUID> check);
}
