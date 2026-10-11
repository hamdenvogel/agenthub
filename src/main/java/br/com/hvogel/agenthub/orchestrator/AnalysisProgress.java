package br.com.hvogel.agenthub.orchestrator;

/**
 * Recebe os eventos de fase do agentic loop (exibidos em {@code GET /api/analysis/{id}}).
 */
@FunctionalInterface
public interface AnalysisProgress {

    AnalysisProgress NONE = (phase, message) -> { };

    void event(String phase, String message);
}
