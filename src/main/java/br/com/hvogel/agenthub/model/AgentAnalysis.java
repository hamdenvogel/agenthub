package br.com.hvogel.agenthub.model;

import java.util.List;

/**
 * Saída estruturada que cada agente pede ao Claude. Os campos {@code id} e {@code agent}
 * de {@link Finding} ficam de fora do schema: quem os define é o Hub.
 *
 * @param summary         resumo da análise da área
 * @param findings        achados baseados em evidências
 * @param recommendations recomendações gerais da área
 * @param confidence      confiança de 0.0 a 1.0 na cobertura e precisão da análise
 */
public record AgentAnalysis(
        String summary,
        List<FindingDraft> findings,
        List<String> recommendations,
        Double confidence) {

    public AgentAnalysis {
        findings = findings != null ? List.copyOf(findings) : List.of();
        recommendations = recommendations != null ? List.copyOf(recommendations) : List.of();
    }

    public record FindingDraft(
            String title,
            String description,
            Severity severity,
            String category,
            List<Evidence> evidence,
            String recommendation) {

        public Finding toFinding(AgentRole role) {
            return new Finding(null, title, description, severity, category, evidence, recommendation, role);
        }
    }
}
