package io.github.lino.chunkloader.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoaderTest {
    @Test
    void tickingIsImmutableAndStopsAtZero() {
        Loader original = loader(21);
        assertEquals(20, original.tick().remainingTicks());
        assertEquals(0, original.tick(1_000).remainingTicks());
        assertEquals(21, original.remainingTicks());
        assertTrue(original.tick(21).isExpired());
        assertEquals(0, loader(0).tick().remainingTicks());
    }

    @Test
    void infiniteDurationSurvivesTicksAndFiniteExtensions() {
        Loader infinite = loader(-1);
        assertTrue(infinite.tick(Long.MAX_VALUE).isInfinite());
        assertTrue(infinite.addTicks(1_000).isInfinite());
        assertTrue(loader(100).addTicks(-1).isInfinite());
        assertFalse(infinite.isExpired());
    }

    @Test
    void extensionsPreserveIdentityAndRejectOverflow() {
        Loader original = loader(100);
        Loader extended = original.addTicks(20);
        assertEquals(120, extended.remainingTicks());
        assertEquals(original.id(), extended.id());
        assertEquals(original.ownerId(), extended.ownerId());
        assertEquals(original.position(), extended.position());
        assertEquals(original.material(), extended.material());
        assertThrows(IllegalArgumentException.class, () -> loader(Long.MAX_VALUE).addTicks(1));
    }

    @Test
    void rejectsInvalidDurationsAndMaterials() {
        assertThrows(IllegalArgumentException.class, () -> loader(-2));
        assertThrows(IllegalArgumentException.class, () -> loader(1).tick(-1));
        assertThrows(IllegalArgumentException.class, () -> loader(1).addTicks(-2));
        assertThrows(IllegalArgumentException.class, () -> loader(1).withRemainingTicks(-2));
        assertThrows(IllegalArgumentException.class, () ->
                new Loader(UUID.randomUUID(), UUID.randomUUID(), position(), 1, " "));
    }

    private static Loader loader(long ticks) {
        return new Loader(UUID.randomUUID(), UUID.randomUUID(), position(), ticks, "LODESTONE");
    }

    private static BlockPosition position() {
        return new BlockPosition(UUID.randomUUID(), 1, 64, -1);
    }
}
