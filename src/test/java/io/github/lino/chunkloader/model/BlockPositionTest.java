package io.github.lino.chunkloader.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class BlockPositionTest {
    @Test
    void chunkCoordinatesUseFloorDivisionForNegativeBlocks() {
        UUID world = UUID.randomUUID();
        assertEquals(-1, new BlockPosition(world, -1, 0, -16).chunkX());
        assertEquals(-1, new BlockPosition(world, -1, 0, -16).chunkZ());
        assertEquals(-2, new BlockPosition(world, -17, 0, 0).chunkX());
        assertEquals(1, new BlockPosition(world, 16, 0, 31).chunkZ());
    }

    @Test
    void rejectsMissingWorld() {
        assertThrows(NullPointerException.class, () -> new BlockPosition(null, 0, 0, 0));
    }
}
