package br.com.hvogel.agenthub.agents;

import java.util.List;

import br.com.hvogel.agenthub.model.AgentResult;

/**
 * Contexto que o Hub entrega a um agente.
 *
 * @param analysisId   análise em andamento
 * @param request      solicitação original do usuário
 * @param projectBrief contexto do projeto selecionado para o papel (ContextSelector)
 * @param priorResults resultados dos agentes dos quais a tarefa depende
 */
public record AgentExecutionContext(
        String analysisId, String request, String projectBrief, List<AgentResult> priorResults) {

    public AgentExecutionContext {
        priorResults = priorResults != null ? List.copyOf(priorResults) : List.of();
    }
}
