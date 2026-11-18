package io.github.mostavassoli.streamjack.core.scenario;

public record Scenario(String apiVersion, String kind, Metadata metadata, Spec spec) {

    public static final String API_VERSION = "streamjack.dev/v1alpha1";
    public static final String KIND = "Scenario";

    public String id() {
        return metadata == null ? null : metadata.id();
    }
}
