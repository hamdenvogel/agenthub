package br.com.hvogel.agenthub.model;

import java.util.Collection;
import java.util.Objects;

public enum Severity {

    CRITICAL(5), HIGH(4), MEDIUM(3), LOW(2), INFO(1);

    private final int rank;

    Severity(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public static Severity highest(Collection<Severity> severities) {
        return severities.stream()
                .filter(Objects::nonNull)
                .reduce((a, b) -> a.rank >= b.rank ? a : b)
                .orElse(INFO);
    }
}
