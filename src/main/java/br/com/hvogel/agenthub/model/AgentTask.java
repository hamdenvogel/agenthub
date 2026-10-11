package br.com.hvogel.agenthub.model;

import java.util.List;
import java.util.UUID;

/**
 * Tarefa delegada pelo Hub a um agente especialista.
 *
 * @param id        identificador da tarefa
 * @param role      agente destino
 * @param objective o que o agente deve analisar
 * @param focus     pontos específicos de atenção
 * @param dependsOn agentes cujo resultado deve existir antes desta tarefa
 * @param round     rodada do agentic loop (1 = delegação inicial)
 * @param feedback  feedback do Evaluator quando a tarefa é re-delegada
 */
public record AgentTask(
        String id,
        AgentRole role,
        String objective,
        List<String> focus,
        List<AgentRole> dependsOn,
        int round,
        String feedback) {

    public AgentTask {
        id = id != null ? id : UUID.randomUUID().toString();
        focus = focus != null ? List.copyOf(focus) : List.of();
        dependsOn = dependsOn != null ? List.copyOf(dependsOn) : List.of();
        round = Math.max(round, 1);
    }

    public static AgentTask initial(AgentRole role, String objective, List<String> focus, List<AgentRole> dependsOn) {
        return new AgentTask(null, role, objective, focus, dependsOn, 1, null);
    }

    public AgentTask followUp(String evaluatorFeedback) {
        return new AgentTask(null, role, objective, focus, List.of(), round + 1, evaluatorFeedback);
    }
}
