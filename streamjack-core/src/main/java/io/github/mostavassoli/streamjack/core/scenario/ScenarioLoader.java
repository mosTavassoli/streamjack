package io.github.mostavassoli.streamjack.core.scenario;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class ScenarioLoader {

    private final ObjectMapper yaml = new ObjectMapper(YAMLFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);

    public Scenario load(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return load(in, file.toString());
        } catch (IOException e) {
            throw new ScenarioException(file.toString(), e);
        }
    }

    public Scenario load(InputStream in, String source) {
        Scenario scenario;
        try {
            scenario = yaml.readValue(in, Scenario.class);
        } catch (IOException e) {
            throw new ScenarioException(source, e);
        }
        if (scenario == null) {
            throw new ScenarioException(source, List.of("file is empty"));
        }
        List<String> problems = ScenarioValidator.validate(scenario);
        if (!problems.isEmpty()) {
            throw new ScenarioException(source, problems);
        }
        return scenario;
    }

    public List<Scenario> loadDirectory(Path directory) {
        List<Path> files;
        try (Stream<Path> stream = Files.list(directory)) {
            files = stream
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".yaml") || name.endsWith(".yml");
                    })
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            throw new ScenarioException(directory.toString(), e);
        }
        List<Scenario> scenarios = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (Path file : files) {
            Scenario scenario = load(file);
            if (ids.contains(scenario.id())) {
                throw new ScenarioException(file.toString(), List.of("duplicate scenario id " + scenario.id()));
            }
            ids.add(scenario.id());
            scenarios.add(scenario);
        }
        return scenarios;
    }
}
