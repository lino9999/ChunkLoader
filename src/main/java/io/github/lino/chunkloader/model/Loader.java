package io.github.lino.chunkloader.model;

import java.util.Objects;
import java.util.UUID;

public record Loader(UUID id, UUID ownerId, BlockPosition position, long remainingTicks, String material) {
    public Loader {
        Objects.requireNonNull(id, "Loader ID is required");
        Objects.requireNonNull(ownerId, "Owner ID is required");
        Objects.requireNonNull(position, "Position is required");
        Objects.requireNonNull(material, "Material is required");
        if (remainingTicks < -1) {
            throw new IllegalArgumentException("Remaining ticks must be nonnegative or -1 for infinite");
        }
        if (material.isBlank() || material.length() > 128) {
            throw new IllegalArgumentException("Material must contain between 1 and 128 characters");
        }
    }

    public boolean isInfinite() {
        return remainingTicks == -1;
    }

    public boolean isExpired() {
        return remainingTicks == 0;
    }

    public Loader withRemainingTicks(long ticks) {
        return new Loader(id, ownerId, position, ticks, material);
    }

    public Loader addTicks(long ticks) {
        if (ticks < -1) {
            throw new IllegalArgumentException("Added ticks must be nonnegative or -1 for infinite");
        }
        if (isInfinite() || ticks == -1) {
            return withRemainingTicks(-1);
        }
        try {
            return withRemainingTicks(Math.addExact(remainingTicks, ticks));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("The resulting duration is too large", exception);
        }
    }

    public Loader tick() {
        return tick(1);
    }

    public Loader tick(long elapsedTicks) {
        if (elapsedTicks < 0) {
            throw new IllegalArgumentException("Elapsed ticks must be nonnegative");
        }
        return isInfinite() ? this : withRemainingTicks(Math.max(0, remainingTicks - elapsedTicks));
    }
}
