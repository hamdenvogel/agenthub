package br.com.hvogel.agenthub.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Relatório final consolidado pelo Hub.
 *
 * <p>{@code consolidation} registra o que cada agente entregou ao agrupador e o destino de cada item;
 * é {@code null} em relatórios gerados antes de existir.
 */
public record AnalysisReport(
        String analysisId,
        String request,
        String projectName,
        Instant generatedAt,
        String executiveSummary,
        ReportSection architecture,
        ReportSection backend,
        ReportSection frontend,
        ReportSection database,
        ReportSection security,
        ReportSection tests,
        ReportSection codeQuality,
        List<Finding> findings,
        List<String> recommendations,
        List<RoadmapItem> roadmap,
        ReportMetadata metadata,
        HubConsolidation consolidation) {

    public AnalysisReport {
        findings = findings != null ? List.copyOf(findings) : List.of();
        recommendations = recommendations != null ? List.copyOf(recommendations) : List.of();
        roadmap = roadmap != null ? List.copyOf(roadmap) : List.of();
    }

    /**
     * @param title      título da seção
     * @param analyzed   {@code false} quando o agente não foi acionado ou falhou
     * @param status     status do agente responsável
     * @param summary    resumo
     * @param findings   achados da seção
     * @param confidence confiança do agente
     */
    public record ReportSection(
            String title, boolean analyzed, AgentStatus status, String summary, List<Finding> findings, double confidence) {

        public ReportSection {
            findings = findings != null ? List.copyOf(findings) : List.of();
        }
    }

    /**
     * @param phase            fase (ex.: {@code Fase 1 - Imediato})
     * @param priority         prioridade
     * @param action           ação
     * @param rationale        motivo
     * @param relatedFindings  ids dos achados relacionados
     */
    public record RoadmapItem(String phase, Severity priority, String action, String rationale, List<String> relatedFindings) {

        public RoadmapItem {
            relatedFindings = relatedFindings != null ? List.copyOf(relatedFindings) : List.of();
        }
    }

    /**
     * @param iterations       rodadas do agentic loop executadas
     * @param durationMs       duração total
     * @param plannerSource    {@code llm} ou {@code rules}
     * @param synthesisSource  {@code llm} ou {@code rules}
     * @param agents           status por agente
     * @param findingsBySeverity contagem por severidade
     * @param limitations      limitações conhecidas desta execução
     * @param usage            tokens consumidos (somatório e por fase: planner, synthesis)
     * @param agentMetrics     desempenho por agente (resultado final de cada um)
     * @param projectRoot      raiz do projeto analisado
     * @param scope            escopo analisado (vazio = projeto inteiro)
     */
    public record ReportMetadata(
            int iterations,
            long durationMs,
            String plannerSource,
            String synthesisSource,
            Map<String, AgentStatus> agents,
            Map<Severity, Long> findingsBySeverity,
            List<String> limitations,
            Map<String, Long> usage,
            List<AgentMetrics> agentMetrics,
            String projectRoot,
            List<String> scope) {

        public ReportMetadata {
            scope = scope != null ? List.copyOf(scope) : List.of();
            agents = agents != null ? Map.copyOf(agents) : Map.of();
            findingsBySeverity = findingsBySeverity != null ? Map.copyOf(findingsBySeverity) : Map.of();
            limitations = limitations != null ? List.copyOf(limitations) : List.of();
            usage = usage != null ? Map.copyOf(usage) : Map.of();
            agentMetrics = agentMetrics != null ? List.copyOf(agentMetrics) : List.of();
        }
    }

    /**
     * @param agent             id do agente
     * @param name              nome de exibição
     * @param status            status final
     * @param round             rodada do resultado final (1 = inicial)
     * @param attempts          tentativas na última execução
     * @param durationMs        duração da última execução (inclui retries)
     * @param llmDurationMs     tempo da chamada ao Claude (inclui tool calls)
     * @param inputTokens       tokens de entrada
     * @param outputTokens      tokens de saída
     * @param toolCalls         chamadas de ferramentas
     * @param toolCallsBySource chamadas por fonte (filesystem, git, ...)
     * @param findings          quantidade de achados
     * @param confidence        confiança
     * @param model             modelo usado
     * @param error             erro, quando não concluiu
     * @param maxOutputTokens     {@code max_tokens} da chamada ("quanto pode responder")
     * @param stopReason          motivo de parada da resposta (ex.: {@code end_turn}, {@code max_tokens})
     * @param contextBudgetTokens context window da execução ("quanto cabe")
     * @param contextPeakTokens   maior turno estimado (prompts + tool schemas + resultados de ferramentas)
     * @param contextExhausted    o orçamento de contexto esgotou e resultados foram cortados
     * @param toolInputRejected   chamadas recusadas por argumentos fora do Tool Input Schema
     * @param schemaViolations    divergências da resposta em relação ao JSON Schema de saída
     */
    public record AgentMetrics(
            String agent, String name, AgentStatus status, long round, long attempts, long durationMs,
            long llmDurationMs, long inputTokens, long outputTokens, long toolCalls,
            Map<String, Long> toolCallsBySource, int findings, double confidence, String model, String error,
            long maxOutputTokens, String stopReason, long contextBudgetTokens, long contextPeakTokens,
            boolean contextExhausted, long toolInputRejected, List<String> schemaViolations) {

        public AgentMetrics {
            toolCallsBySource = toolCallsBySource != null ? Map.copyOf(toolCallsBySource) : Map.of();
            schemaViolations = schemaViolations != null ? List.copyOf(schemaViolations) : List.of();
        }

        public static AgentMetrics from(AgentResult r) {
            Map<String, Long> bySource = new LinkedHashMap<>();
            if (r.metadata().get("toolCallsBySource") instanceof Map<?, ?> m) {
                m.forEach((k, v) -> {
                    if (v instanceof Number n) {
                        bySource.put(String.valueOf(k), n.longValue());
                    }
                });
            }
            List<String> violations = r.metadata().get("schemaViolations") instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList()
                    : List.of();
            Object model = r.metadata().get("model");
            Object stopReason = r.metadata().get("stopReason");
            return new AgentMetrics(r.role().id(), r.agentName(), r.status(), r.metadataLong("round"),
                    r.metadataLong("attempts"), r.metadataLong("durationMs"), r.metadataLong("llmDurationMs"),
                    r.metadataLong("inputTokens"), r.metadataLong("outputTokens"), r.metadataLong("toolCalls"),
                    bySource, r.findings().size(), r.confidence(), model != null ? model.toString() : null, r.error(),
                    r.metadataLong("maxOutputTokens"), stopReason != null ? stopReason.toString() : null,
                    r.metadataLong("contextBudgetTokens"), r.metadataLong("contextPeakTokens"),
                    Boolean.TRUE.equals(r.metadata().get("contextExhausted")), r.metadataLong("toolInputRejected"),
                    violations);
        }
    }
}
