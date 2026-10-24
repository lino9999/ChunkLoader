package io.github.lino.chunkloader.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lino.chunkloader.model.BlockPosition;
import io.github.lino.chunkloader.model.Loader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LoaderStoreTest {
    @TempDir
    Path directory;

    @Test
    void absentStoreReturnsImmutableEmptyListWithoutCreatingFiles() throws IOException {
        Path file = directory.resolve("missing/loaders.json");
        List<Loader> loaded = new LoaderStore(file).load();
        assertTrue(loaded.isEmpty());
        assertFalse(Files.exists(file));
        assertThrows(UnsupportedOperationException.class, () -> loaded.add(loader(1)));
    }

    @Test
    void roundTripsDurationsCoordinatesAndImmutableSnapshots() throws IOException {
        Path file = directory.resolve("nested/loaders.json");
        LoaderStore store = new LoaderStore(file);
        List<Loader> expected = List.of(loader(-1), loader(0), loader(Long.MAX_VALUE));
        store.save(expected);
        List<Loader> loaded = new LoaderStore(file).load();
        assertEquals(expected, loaded);
        assertThrows(UnsupportedOperationException.class, loaded::clear);
        assertFalse(Files.exists(file.resolveSibling("loaders.json.bak")));
        try (var files = Files.list(file.getParent())) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void savesPreviousValidatedDocumentAsBackup() throws IOException {
        Path file = directory.resolve("loaders.json");
        LoaderStore store = new LoaderStore(file);
        List<Loader> first = List.of(loader(20));
        List<Loader> second = List.of(loader(40));
        store.save(first);
        byte[] original = Files.readAllBytes(file);
        store.save(second);
        Path backup = file.resolveSibling("loaders.json.bak");
        assertArrayEquals(original, Files.readAllBytes(backup));
        assertEquals(first, new LoaderStore(backup).load());
        assertEquals(second, store.load());
    }

    @Test
    void supportsShortFileNames() throws IOException {
        LoaderStore store = new LoaderStore(directory.resolve("a"));
        List<Loader> expected = List.of(loader(20));
        store.save(expected);
        assertEquals(expected, store.load());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "[]", "{\"schemaVersion\":2,\"loaders\":[]}",
            "{\"schemaVersion\":1,\"loaders\":null}", "{\"schemaVersion\":1,\"loaders\":[]} true",
            "{schemaVersion:1,loaders:[]}", "{\"schemaVersion\":1,\"loaders\":[{}]}"})
    void refusesToLoadOrOverwriteCorruptOrUnsupportedInput(String input) throws IOException {
        Path file = directory.resolve("loaders.json");
        Files.writeString(file, input);
        LoaderStore store = new LoaderStore(file);
        assertThrows(IOException.class, store::load);
        assertThrows(IOException.class, () -> store.save(List.of(loader(1))));
        assertEquals(input, Files.readString(file));
        assertFalse(Files.exists(file.resolveSibling("loaders.json.bak")));
    }

    @Test
    void refusesMalformedUtf8AndPreservesOriginalBytes() throws IOException {
        Path file = directory.resolve("loaders.json");
        byte[] invalid = {(byte) 0xc3, (byte) 0x28};
        Files.write(file, invalid);
        LoaderStore store = new LoaderStore(file);
        assertThrows(IOException.class, store::load);
        assertThrows(IOException.class, () -> store.save(List.of()));
        assertArrayEquals(invalid, Files.readAllBytes(file));
    }

    @Test
    void rejectsDuplicateIdsAndPositionsWithoutTouchingExistingData() throws IOException {
        Path file = directory.resolve("loaders.json");
        LoaderStore store = new LoaderStore(file);
        Loader first = loader(20);
        store.save(List.of(first));
        byte[] original = Files.readAllBytes(file);
        Loader sameId = new Loader(first.id(), first.ownerId(), loader(1).position(), 40, "LODESTONE");
        Loader samePosition = new Loader(UUID.randomUUID(), first.ownerId(), first.position(), 40, "LODESTONE");
        assertThrows(IOException.class, () -> store.save(List.of(first, sameId)));
        assertThrows(IOException.class, () -> store.save(List.of(first, samePosition)));
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test
    void validatesPersistedEntriesIncludingDuplicatePositionsAndOutOfRangeNumbers() throws IOException {
        Path file = directory.resolve("loaders.json");
        LoaderStore store = new LoaderStore(file);
        Loader loader = loader(20);
        store.save(List.of(loader));
        String original = Files.readString(file);
        String entry = original.substring(original.indexOf('{', original.indexOf("\"loaders\"")), original.lastIndexOf('}')).strip();
        entry = entry.substring(0, entry.lastIndexOf(']')).strip();
        Files.writeString(file, "{\"schemaVersion\":1,\"loaders\":[" + entry + "," + entry + "]}");
        assertThrows(IOException.class, store::load);
        Files.writeString(file, original.replace("\"remainingTicks\": 20", "\"remainingTicks\": 9223372036854775808"));
        assertThrows(IOException.class, store::load);
        Files.writeString(file, original.replace("\"remainingTicks\": 20", "\"remainingTicks\": 1.5"));
        assertThrows(IOException.class, store::load);
        Files.writeString(file, original.replace("\"x\": -17", "\"x\": 2147483648"));
        assertThrows(IOException.class, store::load);
    }

    @Test
    void rejectsNullEntriesWithCheckedFailure() throws IOException {
        LoaderStore store = new LoaderStore(directory.resolve("loaders.json"));
        List<Loader> invalid = new ArrayList<>();
        invalid.add(null);
        assertThrows(IOException.class, () -> store.save(invalid));
    }

    private static Loader loader(long ticks) {
        return new Loader(UUID.randomUUID(), UUID.randomUUID(),
                new BlockPosition(UUID.randomUUID(), -17, -64, Integer.MAX_VALUE), ticks, "LODESTONE");
    }
}
