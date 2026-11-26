package io.github.mostavassoli.streamjack.bench;

import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Input checks and file handling for the command line, kept here so they are tested like the rest. */
public final class Setup {

    static final Path SCENARIO_DIR = Path.of("experiments", "defense-levels-v2", "scenarios");
    static final Path DUPLICATE_DELIVERY = Path.of("scenarios", "sj-004-duplicate-delivery.yaml");
    static final Set<String> MODES = Set.of("NEUTRAL", "PROMPT_ONLY", "HARDENED", "VULNERABLE");
    static final String MOCK_KEY = "mock-key-not-real";

    private Setup() {
    }

    /** The benchmark scenarios in a stable order, optionally only the named ones. */
    public static List<Path> scenarioFiles(Path repository, List<String> only) {
        Path directory = repository.resolve(SCENARIO_DIR);
        if (!Files.isDirectory(directory) || !Files.exists(repository.resolve(DUPLICATE_DELIVERY))) {
            throw new IllegalArgumentException("Benchmark scenarios not found; run from the repository root.");
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> yaml = Files.list(directory)) {
            yaml.filter(file -> file.getFileName().toString().endsWith(".yaml")).sorted().forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        files.add(repository.resolve(DUPLICATE_DELIVERY));
        if (only.isEmpty()) {
            return files;
        }
        List<Path> selected = files.stream().filter(file -> only.contains(Manifest.scenarioName(file))).toList();
        if (selected.size() != only.size()) {
            throw new IllegalArgumentException("Unknown scenario in --scenario: " + only);
        }
        return selected;
    }

    /**
     * The key to give the agent. A mock never gets a real key. Otherwise OPENAI_API_KEY, or a key file that must be
     * outside the repository, so the key cannot be committed by accident.
     */
    public static String apiKey(String baseUrl, String environmentKey, Path keyFile, Path repository) {
        if (baseUrl != null) {
            if (!baseUrl.matches("http://127\\.0\\.0\\.1:\\d+/.*")) {
                throw new IllegalArgumentException("--openai-base-url is only for a local mock on 127.0.0.1");
            }
            return MOCK_KEY;
        }
        if (environmentKey != null && !environmentKey.isBlank()) {
            return environmentKey.trim();
        }
        Path file = keyFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("No API key: set OPENAI_API_KEY or create " + file);
        }
        try {
            Path actual = file.toRealPath();
            if (file.startsWith(repository.toAbsolutePath().normalize()) || actual.startsWith(repository.toRealPath())) {
                throw new IllegalArgumentException("The API key file must be outside the repository.");
            }
            String key = Files.readString(actual, StandardCharsets.UTF_8).trim();
            if (key.isEmpty()) {
                throw new IllegalArgumentException("The API key file is empty: " + file);
            }
            return key;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the API key file", e);
        }
    }

    public static BenchSettings settings(String model, String baseUrl, int replicates, List<String> modes, long seed,
            double inputPrice, double outputPrice, double trialCostReserve, List<Path> scenarioFiles, Path agentDir) {
        List<String> normalized = modes.stream().map(mode -> mode.trim().toUpperCase(Locale.ROOT)).toList();
        if (!MODES.containsAll(normalized) || normalized.isEmpty() || normalized.stream().distinct().count() != normalized.size()) {
            throw new IllegalArgumentException("Use distinct modes from " + MODES);
        }
        if (!model.matches("[a-zA-Z0-9][a-zA-Z0-9.-]{0,127}")) {
            throw new IllegalArgumentException("Use a valid OpenAI model name.");
        }
        if (!Scorer.SCENARIOS.keySet().containsAll(scenarioFiles.stream().map(Manifest::scenarioName).toList())) {
            throw new IllegalArgumentException("Unknown benchmark scenario.");
        }
        if (replicates < 1) {
            throw new IllegalArgumentException("--replicates must be at least 1");
        }
        if (!Files.isRegularFile(agentDir.resolve("quarkus-run.jar"))) {
            throw new IllegalArgumentException("No agent build in " + agentDir + " (expected quarkus-run.jar)");
        }
        return new BenchSettings(model, baseUrl == null ? "openai" : baseUrl, replicates, normalized, seed, inputPrice,
                outputPrice, trialCostReserve, scenarioFiles.stream().map(Manifest::scenarioName).toList(),
                Map.of("scenarios", Fingerprint.of(scenarioFiles), "agent", Fingerprint.of(List.of(agentDir)),
                        "harness", Fingerprint.ofHarness()));
    }

    public static void writeScores(Path run, List<Scorer.Row> rows) throws IOException {
        StateFiles.write(run.resolve("scores.json"), rows);
        StringBuilder csv = new StringBuilder();
        var fields = Scorer.Row.class.getRecordComponents();
        for (int i = 0; i < fields.length; i++) {
            csv.append(i == 0 ? "" : ",").append(fields[i].getName());
        }
        csv.append('\n');
        for (Scorer.Row row : rows) {
            for (int i = 0; i < fields.length; i++) {
                Object value;
                try {
                    value = fields[i].getAccessor().invoke(row);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
                String text = String.valueOf(value);
                csv.append(i == 0 ? "" : ",")
                        .append(text.contains(",") || text.contains("\"") || text.contains("\r") || text.contains("\n")
                                ? "\"" + text.replace("\"", "\"\"") + "\"" : text);
            }
            csv.append('\n');
        }
        Files.writeString(run.resolve("scores.csv"), csv, StandardCharsets.UTF_8);
    }

    public static List<Scorer.Row> readScores(List<Path> files) throws IOException {
        List<Scorer.Row> rows = new ArrayList<>();
        for (Path file : files) {
            rows.addAll(StateFiles.JSON.readValue(file.toFile(), new TypeReference<List<Scorer.Row>>() { }));
        }
        return rows;
    }
}
