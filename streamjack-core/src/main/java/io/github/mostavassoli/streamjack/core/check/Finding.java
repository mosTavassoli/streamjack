package io.github.mostavassoli.streamjack.core.check;

public record Finding(String check, Category category, boolean passed, String message) {

    public static Finding pass(String check, Category category, String message) {
        return new Finding(check, category, true, message);
    }

    public static Finding fail(String check, Category category, String message) {
        return new Finding(check, category, false, message);
    }
}
