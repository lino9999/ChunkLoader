package io.github.lino.chunkloader.model;

import java.util.Objects;
import java.util.UUID;

public record BlockPosition(UUID worldId, int x, int y, int z) {
    public BlockPosition {
        Objects.requireNonNull(worldId, "World ID is required");
    }

    public int chunkX() {
        return x >> 4;
    }

    public int chunkZ() {
        return z >> 4;
    }
}
