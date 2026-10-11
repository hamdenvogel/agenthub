package br.com.hvogel.agenthub.agents;

import java.util.List;

import br.com.hvogel.agenthub.model.AgentResult;
import br.com.hvogel.agenthub.model.AgentRole;
import br.com.hvogel.agenthub.model.AgentTask;

/**
 * Agente especialista (spoke). Recebe uma tarefa do Hub, usa somente as ferramentas
 * permitidas ao seu papel e devolve um resultado estruturado.
 */
public interface Agent {

    AgentRole role();

    default String name() {
        return role().displayName();
    }

    /** Descrição usada pelo Planner para decidir a delegação. */
    String description();

    /** System prompt especializado (configurável por recurso). */
    String instructions();

    /** Fontes de ferramentas permitidas a este agente. */
    List<String> toolSources();

    /**
     * Executa a tarefa. Falhas são sinalizadas por exceção; retry, timeout e
     * conversão em resultado de falha são responsabilidade do Hub (TaskDelegator).
     */
    AgentResult execute(AgentTask task, AgentExecutionContext context);
}
