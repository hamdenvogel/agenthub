package br.com.hvogel.agenthub.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Papéis especialistas conhecidos pelo Hub. O {@link #id()} é a chave usada em
 * {@code agenthub.agents.<id>} e nos nomes de prompt.
 */
public enum AgentRole {

    ARCHITECTURE("Architecture Agent", "Architecture"),
    BACKEND("Backend Agent", "Backend"),
    FRONTEND("Frontend Agent", "Frontend"),
    DATABASE("Database Agent", "Database"),
    SECURITY("Security Agent", "Security"),
    TEST("Test Agent", "Tests");

    private final String displayName;
    private final String reportSection;

    AgentRole(String displayName, String reportSection) {
        this.displayName = displayName;
        this.reportSection = reportSection;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String displayName() {
        return displayName;
    }

    public String reportSection() {
        return reportSection;
    }

    public String findingPrefix() {
        return switch (this) {
            case ARCHITECTURE -> "ARC";
            case BACKEND -> "BCK";
            case FRONTEND -> "FRT";
            case DATABASE -> "DB";
            case SECURITY -> "SEC";
            case TEST -> "TST";
        };
    }

    public static Optional<AgentRole> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT)
                .replace("_AGENT", "").replace(" AGENT", "").replace("AGENT", "").trim();
        if ("TESTS".equals(normalized)) {
            normalized = "TEST";
        }
        for (AgentRole role : values()) {
            if (role.name().equals(normalized)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
