package com.example.aireviewer.domain;

public enum Severity {

    HIGH(4), MEDIUM(3), LOW(2), NITPICK(1);

    private final int weight;

    Severity(int weight) {
        this.weight = weight;
    }

    public int getWeight() {
        return weight;
    }

    public static Severity fromString(String value) {
        if (value == null) return LOW;
        return switch (value.trim().toUpperCase()) {
            case "HIGH"    -> HIGH;
            case "MEDIUM"  -> MEDIUM;
            case "LOW"     -> LOW;
            case "NITPICK" -> NITPICK;
            default        -> LOW;
        };
    }
}
