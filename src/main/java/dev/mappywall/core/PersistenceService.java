package dev.mappywall.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Optional;

public final class PersistenceService {
    private final Gson gson;

    public PersistenceService() {
        this.gson = new GsonBuilder()
                .registerTypeAdapter(Instant.class, (JsonSerializer<Instant>) (value, type, context) ->
                        new JsonPrimitive(value.toString()))
                .registerTypeAdapter(Instant.class, (JsonDeserializer<Instant>) (json, type, context) ->
                        Instant.parse(json.getAsString()))
                .setPrettyPrinting()
                .create();
    }

    public Path projectPath(Path configRoot, String serverKey, String dimension, String projectId) {
        return configRoot
                .resolve(sanitize(serverKey))
                .resolve(sanitize(dimension))
                .resolve(sanitize(projectId) + ".json");
    }

    public void save(Path path, MapWallSave save) throws IOException {
        Path target = path.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("save path has no parent: " + path);
        }
        Files.createDirectories(parent);

        Path temporary = Files.createTempFile(parent, "." + target.getFileName() + "-", ".tmp");
        try {
            writeDurably(temporary, gson.toJson(save).getBytes(StandardCharsets.UTF_8));

            // Never replace a known-good backup with a corrupt/truncated primary file.
            if (readValid(target).isPresent()) {
                Path backupTemporary = Files.createTempFile(parent, "." + target.getFileName() + "-", ".bak.tmp");
                try {
                    writeDurably(backupTemporary, Files.readAllBytes(target));
                    replace(backupTemporary, backupPath(target));
                } finally {
                    Files.deleteIfExists(backupTemporary);
                }
            }

            replace(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Optional<MapWallSave> load(Path path) throws IOException {
        Path target = path.toAbsolutePath().normalize();
        // An absent primary means the project was intentionally removed. Backups are
        // recovery data for a present-but-corrupt primary, not a second live project.
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        Optional<MapWallSave> primary = readValid(target);
        if (primary.isPresent()) {
            return primary;
        }
        return readValid(backupPath(target));
    }

    public Path backupPath(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException("path must name a file");
        }
        return path.resolveSibling(fileName + ".bak");
    }

    private Optional<MapWallSave> readValid(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            try {
                return Optional.ofNullable(gson.fromJson(reader, MapWallSave.class));
            } catch (RuntimeException malformedOrInvalidSave) {
                return Optional.empty();
            }
        }
    }

    private void writeDurably(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private void replace(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sanitize(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                builder.append(c);
            } else {
                builder.append('_');
            }
        }
        return builder.toString();
    }
}
