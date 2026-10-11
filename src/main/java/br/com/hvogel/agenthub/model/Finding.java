package br.com.hvogel.agenthub.model;

import java.util.List;

/**
 * @param id             identificador atribuído na agregação (ex.: {@code SEC-001})
 * @param title          título curto
 * @param description    descrição do problema observado
 * @param severity       severidade
 * @param category       categoria livre (ex.: {@code code-quality}, {@code authentication})
 * @param evidence       evidências no código
 * @param recommendation ação recomendada
 * @param agent          papel que reportou o achado (preenchido pelo Hub)
 */
public record Finding(
        String id,
        String title,
        String description,
        Severity severity,
        String category,
        List<Evidence> evidence,
        String recommendation,
        AgentRole agent) {

    public Finding {
        severity = severity != null ? severity : Severity.INFO;
        evidence = evidence != null ? List.copyOf(evidence) : List.of();
    }

    public boolean hasEvidence() {
        return evidence.stream().anyMatch(Evidence::hasFile);
    }

    public Finding withAgent(AgentRole role) {
        return new Finding(id, title, description, severity, category, evidence, recommendation, role);
    }

    public Finding withId(String newId) {
        return new Finding(newId, title, description, severity, category, evidence, recommendation, agent);
    }
}
