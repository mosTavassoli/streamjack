package io.github.mostavassoli.streamjack.bench;

/**
 * One planned trial: a scenario in one mode, one replicate.
 *
 * @param events number of tickets in the scenario, so the number of model calls a complete trial makes
 */
public record Trial(String id, String mode, String scenario, String scenarioFile, int replicate, int events) {
}
