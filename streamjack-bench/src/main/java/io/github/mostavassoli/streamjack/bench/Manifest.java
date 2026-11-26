package io.github.mostavassoli.streamjack.bench;

import io.github.mostavassoli.streamjack.core.scenario.ScenarioLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The frozen list of trials. Each replicate is one shuffled round of every mode and scenario, so drift during a long
 * run affects every cell equally. The shuffle is seeded; once written, the saved manifest is the source of truth.
 */
public record Manifest(List<Trial> trials) {

    public Manifest {
        trials = List.copyOf(trials);
    }

    static Manifest plan(List<Path> scenarioFiles, List<String> modes, int replicates, long seed) {
        ScenarioLoader loader = new ScenarioLoader();
        record Cell(String mode, Path file, int events) {
        }
        List<Cell> cells = new ArrayList<>();
        for (String mode : modes) {
            for (Path file : scenarioFiles) {
                // Loading validates the scenario and gives the number of tickets it sends.
                cells.add(new Cell(mode, file, loader.load(file).spec().events().size()));
            }
        }
        Random random = new Random(seed);
        List<Trial> trials = new ArrayList<>();
        int order = 0;
        for (int replicate = 1; replicate <= replicates; replicate++) {
            List<Cell> round = new ArrayList<>(cells);
            Collections.shuffle(round, random);
            for (Cell cell : round) {
                order++;
                String scenario = scenarioName(cell.file());
                trials.add(new Trial(String.format(Locale.ROOT, "%04d-%s-%s-r%d", order, cell.mode(), scenario, replicate),
                        cell.mode(), scenario, cell.file().toAbsolutePath().normalize().toString(), replicate, cell.events()));
            }
        }
        return new Manifest(trials);
    }

    static String scenarioName(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".yaml") ? name.substring(0, name.length() - ".yaml".length()) : name;
    }
}
