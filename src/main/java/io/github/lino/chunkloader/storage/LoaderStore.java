package io.github.lino.chunkloader.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.github.lino.chunkloader.model.BlockPosition;
import io.github.lino.chunkloader.model.Loader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class LoaderStore {
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_ENTRIES = 100_000;
    private static final long MAX_FILE_BYTES = 64L * 1_024 * 1_024;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path file;
    private final Path backup;

    public LoaderStore(Path file) {
        this.file = Objects.requireNonNull(file, "Store path is required").toAbsolutePath().normalize();
        this.backup = this.file.resolveSibling(this.file.getFileName() + ".bak");
    }

    public synchronized List<Loader> load() throws IOException {
        if (Files.notExists(file)) {
            return List.of();
        }
        return decode(readFile());
    }

    public synchronized void save(Collection<Loader> loaders) throws IOException {
        List<Loader> snapshot;
        try {
            snapshot = List.copyOf(loaders);
            validate(snapshot);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid loader data: " + exception.getMessage(), exception);
        }
        byte[] previous = null;
        if (!Files.notExists(file)) {
            previous = readFile();
            decode(previous);
        }
        byte[] encoded = encode(snapshot);
        if (encoded.length > MAX_FILE_BYTES) {
            throw new IOException("Loader data exceeds the maximum file size");
        }
        Files.createDirectories(file.getParent());
        if (previous != null) {
            atomicWrite(backup, previous);
        }
        atomicWrite(file, encoded);
    }

    private byte[] readFile() throws IOException {
        if (Files.size(file) > MAX_FILE_BYTES) {
            throw new IOException("Loader store exceeds the maximum file size: " + file);
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Loader store exceeds the maximum file size: " + file);
        }
        return bytes;
    }

    private List<Loader> decode(byte[] bytes) throws IOException {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            JsonReader reader = new JsonReader(new StringReader(text));
            reader.setStrictness(Strictness.STRICT);
            JsonObject document = JsonParser.parseReader(reader).getAsJsonObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException("Unexpected content after the loader store document");
            }
            if (number(document, "schemaVersion") != SCHEMA_VERSION) {
                throw new IllegalArgumentException("Unsupported loader store schema version");
            }
            JsonElement value = required(document, "loaders");
            if (!value.isJsonArray()) {
                throw new IllegalArgumentException("The loaders field must be an array");
            }
            JsonArray entries = value.getAsJsonArray();
            if (entries.size() > MAX_ENTRIES) {
                throw new IllegalArgumentException("Too many loader entries");
            }
            List<Loader> loaders = new ArrayList<>(entries.size());
            for (JsonElement entry : entries) {
                JsonObject loader = entry.getAsJsonObject();
                JsonObject position = required(loader, "position").getAsJsonObject();
                loaders.add(new Loader(uuid(loader, "id"), uuid(loader, "ownerId"),
                        new BlockPosition(uuid(position, "worldId"), coordinate(position, "x"),
                                coordinate(position, "y"), coordinate(position, "z")),
                        number(loader, "remainingTicks"), string(loader, "material")));
            }
            validate(loaders);
            return List.copyOf(loaders);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid loader store " + file + ": " + exception.getMessage(), exception);
        }
    }

    private byte[] encode(List<Loader> loaders) {
        JsonObject document = new JsonObject();
        document.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonArray entries = new JsonArray(loaders.size());
        for (Loader loader : loaders) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", loader.id().toString());
            entry.addProperty("ownerId", loader.ownerId().toString());
            JsonObject position = new JsonObject();
            position.addProperty("worldId", loader.position().worldId().toString());
            position.addProperty("x", loader.position().x());
            position.addProperty("y", loader.position().y());
            position.addProperty("z", loader.position().z());
            entry.add("position", position);
            entry.addProperty("remainingTicks", loader.remainingTicks());
            entry.addProperty("material", loader.material());
            entries.add(entry);
        }
        document.add("loaders", entries);
        return (GSON.toJson(document) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    }

    private static void validate(Collection<Loader> loaders) {
        if (loaders.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Too many loader entries");
        }
        Set<UUID> ids = new HashSet<>();
        Set<BlockPosition> positions = new HashSet<>();
        for (Loader loader : loaders) {
            Objects.requireNonNull(loader, "Loader must not be null");
            if (!ids.add(loader.id())) {
                throw new IllegalArgumentException("Duplicate loader ID: " + loader.id());
            }
            if (!positions.add(loader.position())) {
                throw new IllegalArgumentException("Duplicate loader position: " + loader.position());
            }
        }
    }

    private static JsonElement required(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
        return value;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = required(object, key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Field must be a string: " + key);
        }
        return value.getAsString();
    }

    private static UUID uuid(JsonObject object, String key) {
        String value = string(object, key);
        UUID id = UUID.fromString(value);
        if (!id.toString().equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("Field must contain a complete UUID: " + key);
        }
        return id;
    }

    private static long number(JsonObject object, String key) {
        JsonElement value = required(object, key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Field must be an integer: " + key);
        }
        return value.getAsBigDecimal().longValueExact();
    }

    private static int coordinate(JsonObject object, String key) {
        return Math.toIntExact(number(object, key));
    }

    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), "loader-" + target.getFileName() + ".", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
