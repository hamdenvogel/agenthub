package br.com.hvogel.agenthub.model;

import java.util.List;

/**
 * Plano do Hub para uma solicitação.
 *
 * @param objective    objetivo consolidado
 * @param rationale    justificativa da seleção de agentes
 * @param tasks        tarefas a delegar
 * @param source       {@code llm} ou {@code rules}
 * @param inputTokens  tokens de entrada gastos no planejamento
 * @param outputTokens tokens de saída gastos no planejamento
 */
public record AnalysisPlan(
        String objective, String rationale, List<AgentTask> tasks, String source, long inputTokens, long outputTokens) {

    public AnalysisPlan {
        tasks = tasks != null ? List.copyOf(tasks) : List.of();
    }

    public List<AgentRole> roles() {
        return tasks.stream().map(AgentTask::role).distinct().toList();
    }
}
