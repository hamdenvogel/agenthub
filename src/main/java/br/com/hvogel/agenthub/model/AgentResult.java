package br.com.hvogel.agenthub.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resultado estruturado de um agente, conforme o contrato do Hub.
 *
 * @param agentName       nome do agente
 * @param role            papel do agente
 * @param task            tarefa executada
 * @param status          status da execução
 * @param summary         resumo produzido pelo agente
 * @param findings        achados
 * @param severity        maior severidade entre os achados
 * @param recommendations recomendações gerais
 * @param evidence        arquivos citados como evidência (distintos)
 * @param confidence      confiança de 0.0 a 1.0
 * @param metadata        duração, tentativas, ferramentas, tokens, erro
 */
public record AgentResult(
        String agentName,
        AgentRole role,
        AgentTask task,
        AgentStatus status,
        String summary,
        List<Finding> findings,
        Severity severity,
        List<String> recommendations,
        List<String> evidence,
        double confidence,
        Map<String, Object> metadata) {

    public AgentResult {
        findings = findings != null ? List.copyOf(findings) : List.of();
        recommendations = recommendations != null ? List.copyOf(recommendations) : List.of();
        evidence = evidence != null ? List.copyOf(evidence) : List.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        severity = severity != null ? severity : Severity.INFO;
    }

    public static AgentResult fromAnalysis(AgentTask task, AgentAnalysis analysis, Map<String, Object> metadata) {
        List<Finding> findings = analysis.findings().stream()
                .filter(Objects::nonNull)
                .map(f -> f.toFinding(task.role()))
                .toList();
        List<String> files = findings.stream()
                .flatMap(f -> f.evidence().stream())
                .filter(Evidence::hasFile)
                .map(Evidence::file)
                .distinct()
                .toList();
        double confidence = analysis.confidence() != null
                ? Math.clamp(analysis.confidence(), 0.0, 1.0)
                : 0.0;
        return new AgentResult(
                task.role().displayName(), task.role(), task, AgentStatus.SUCCESS,
                analysis.summary(), findings,
                Severity.highest(findings.stream().map(Finding::severity).toList()),
                analysis.recommendations(), files, confidence, metadata);
    }

    public static AgentResult failure(AgentTask task, AgentStatus status, String error, Map<String, Object> metadata) {
        Map<String, Object> meta = new LinkedHashMap<>(metadata);
        meta.put("error", error != null ? error : "erro desconhecido");
        return new AgentResult(
                task.role().displayName(), task.role(), task, status,
                null, List.of(), Severity.INFO, List.of(), List.of(), 0.0, meta);
    }

    public AgentResult withMetadata(Map<String, Object> extra) {
        Map<String, Object> merged = new LinkedHashMap<>(metadata);
        merged.putAll(extra);
        return new AgentResult(agentName, role, task, status, summary, findings, severity, recommendations,
                evidence, confidence, merged);
    }

    public long metadataLong(String key) {
        return metadata.get(key) instanceof Number n ? n.longValue() : 0L;
    }

    public String error() {
        Object error = metadata.get("error");
        return error != null ? error.toString() : null;
    }
}
