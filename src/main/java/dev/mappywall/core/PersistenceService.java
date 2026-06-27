package dev.mappywall.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
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
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(path)) {
            gson.toJson(save, writer);
        }
    }

    public Optional<MapWallSave> load(Path path) throws IOException {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            return Optional.of(gson.fromJson(reader, MapWallSave.class));
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

