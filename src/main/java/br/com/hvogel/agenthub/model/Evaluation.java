package br.com.hvogel.agenthub.model;

import java.util.List;

/**
 * Decisão do Evaluator ao fim de uma rodada.
 *
 * @param sufficient {@code true} encerra o loop e segue para a síntese
 * @param followUps  tarefas a re-delegar quando insuficiente
 * @param notes      observações (vão para as limitações do relatório quando não resolvidas)
 */
public record Evaluation(boolean sufficient, List<AgentTask> followUps, List<String> notes) {

    public Evaluation {
        followUps = followUps != null ? List.copyOf(followUps) : List.of();
        notes = notes != null ? List.copyOf(notes) : List.of();
    }
}
