package io.github.lino.chunkloader.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DurationsTest {
    @ParameterizedTest
    @CsvSource({"90s,1800", "30m,36000", "2h,144000", "1d,1728000", "5,6000", "INF,-1", "infinite,-1"})
    void parsesSupportedCommandDurations(String input, long expected) {
        assertEquals(expected, Durations.parse(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "0s", "-1", "1.5h", "1w", "forever", "1h30m", "9223372036854775807d", "999999999999999999999999s"})
    void rejectsInvalidAndOverflowingDurations(String input) {
        assertThrows(IllegalArgumentException.class, () -> Durations.parse(input));
    }

    @Test
    void formatsRoundedSecondsWithoutOverflow() {
        assertEquals("Infinite", Durations.format(-1));
        assertEquals("0s", Durations.format(0));
        assertEquals("1s", Durations.format(1));
        assertEquals("2s", Durations.format(21));
        assertEquals("1d 2h 3m 4s", Durations.format((86_400 + 7_200 + 180 + 4) * 20L));
        assertEquals("5337599558365d 46m 31s", Durations.format(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> Durations.format(-2));
    }
}
