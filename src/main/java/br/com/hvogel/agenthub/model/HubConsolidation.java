package br.com.hvogel.agenthub.model;

import java.util.List;

/**
 * O que chegou ao agrupador (Hub) e o que ele fez com cada item: cada agente entrega o Structured Output
 * ({@link AgentAnalysis}) já convertido em {@link AgentResult}; o Hub deduplica achados e recomendações
 * entre agentes e atribui os ids do relatório.
 *
 * @param totals totais da consolidação
 * @param agents saída de cada agente, na ordem dos papéis
 * @param items  cada achado e recomendação recebidos, com o destino na consolidação
 */
public record HubConsolidation(Totals totals, List<AgentOutput> agents, List<Item> items) {

    public HubConsolidation {
        agents = agents != null ? List.copyOf(agents) : List.of();
        items = items != null ? List.copyOf(items) : List.of();
    }

    /**
     * @param findingsMerged         achados recebidos que eram duplicados de outro (mesmo título e arquivo)
     * @param recommendationsKept    recomendações distintas após remover repetições
     * @param evidenceFiles          arquivos distintos citados como evidência
     */
    public record Totals(int agents, int succeeded, int failed, int findingsReceived, int findingsKept,
                         int findingsMerged, int recommendationsReceived, int recommendationsKept, int evidenceFiles) {
    }

    /**
     * @param outputSchema     record do Structured Output entregue ao Hub ({@code null} se o agente não concluiu)
     * @param findingsReceived achados entregues pelo agente
     * @param findingsKept     achados do agente que ficaram no relatório
     * @param findingsMerged   achados do agente absorvidos por um achado equivalente
     * @param schemaViolations divergências da resposta em relação ao JSON Schema de saída
     */
    public record AgentOutput(String agent, String name, AgentStatus status, long round, String outputSchema,
                              String summary, double confidence, Severity severity, int findingsReceived,
                              int findingsKept, int findingsMerged, int recommendations, int evidenceFiles,
                              int schemaViolations, String error) {
    }

    public enum ItemType { FINDING, RECOMMENDATION }

    public enum Outcome { KEPT, DUPLICATE }

    /**
     * @param severity  severidade (só achados)
     * @param file      arquivo principal da evidência (só achados)
     * @param findingId id no relatório; para achados duplicados, o id do achado que o absorveu
     */
    public record Item(ItemType type, String agent, String agentName, Severity severity, String title,
                       String category, String file, Outcome outcome, String findingId) {
    }
}
