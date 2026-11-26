package io.github.mostavassoli.streamjack.bench;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Reads and writes the benchmark's JSON state. A lost or half-written state file could let a billed attempt be
 * repeated, so writes go to a temporary file that is then moved into place, and a file that exists but cannot be read
 * is an error rather than something to skip.
 */
public final class StateFiles {

    static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private StateFiles() {
    }

    public static void write(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            JSON.writeValue(temporary.toFile(), value);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    public static <T> T read(Path file, Class<T> type) {
        try {
            T value = JSON.readValue(file.toFile(), type);
            if (value == null) {
                throw new IllegalStateException("Empty state file " + file);
            }
            return value;
        } catch (IOException e) {
            throw new UncheckedIOException("Unreadable state file " + file, e);
        }
    }
}
