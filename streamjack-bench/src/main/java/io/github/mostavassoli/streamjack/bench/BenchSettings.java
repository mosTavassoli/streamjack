package io.github.mostavassoli.streamjack.bench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything that shapes a run's results. A run directory is started with one set of settings; resuming with any
 * difference, including a changed scenario file or agent build, is refused.
 *
 * @param endpoint {@code openai} for the real API, {@code mock} for a local mock (results are not comparable)
 * @param trialCostReserve worst-case cost of one trial, used before each trial and for attempts with unclear cost
 * @param fingerprints SHA-256 of the scenario files, the agent build, and the harness code
 */
public record BenchSettings(
        String model,
        String endpoint,
        int replicates,
        List<String> modes,
        long seed,
        double inputPrice,
        double outputPrice,
        double trialCostReserve,
        List<String> scenarios,
        Map<String, String> fingerprints) {

    public BenchSettings {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(endpoint, "endpoint");
        modes = List.copyOf(modes);
        scenarios = List.copyOf(scenarios);
        fingerprints = Map.copyOf(fingerprints);
        if (!positive(inputPrice) || !positive(outputPrice) || !positive(trialCostReserve)) {
            throw new IllegalArgumentException("Prices and the trial cost reserve must be finite and positive");
        }
    }

    private static boolean positive(double amount) {
        return Double.isFinite(amount) && amount > 0;
    }

    /** Names of the settings that differ from {@code other}; empty if they match. */
    List<String> differences(BenchSettings other) {
        Map<String, Object> mine = asMap();
        Map<String, Object> theirs = other.asMap();
        List<String> different = new ArrayList<>();
        mine.forEach((name, value) -> {
            if (!Objects.equals(value, theirs.get(name))) {
                different.add(name);
            }
        });
        return different;
    }

    private Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("model", model);
        map.put("endpoint", endpoint);
        map.put("replicates", replicates);
        map.put("modes", modes);
        map.put("seed", seed);
        map.put("inputPrice", inputPrice);
        map.put("outputPrice", outputPrice);
        map.put("trialCostReserve", trialCostReserve);
        map.put("scenarios", scenarios);
        fingerprints.forEach((name, hash) -> map.put("fingerprint " + name, hash));
        return map;
    }
}
