package io.github.mostavassoli.streamjack.core.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ReportWriter {

    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public String toJson(RunReport report) {
        try {
            return json.writeValueAsString(report);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void write(RunReport report, Path file) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, toJson(report));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
