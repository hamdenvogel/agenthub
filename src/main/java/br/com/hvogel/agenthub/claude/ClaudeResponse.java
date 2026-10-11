package br.com.hvogel.agenthub.claude;

import java.util.List;

/**
 * @param entity              saída estruturada convertida
 * @param model               modelo que respondeu
 * @param inputTokens         tokens de entrada (prompt + histórico de tool calls), medidos pela API
 * @param outputTokens        tokens de saída, medidos pela API
 * @param durationMs          duração da chamada (inclui o loop interno de tool calling)
 * @param maxOutputTokens     {@code max_tokens} usado na chamada
 * @param stopReason          motivo de parada informado pela API (ex.: {@code end_turn})
 * @param contextBudgetTokens orçamento de contexto efetivo da execução
 * @param contextPeakTokens   maior turno estimado (parte fixa + resultados de ferramentas)
 * @param schemaViolations    divergências da resposta em relação ao JSON Schema (a conversão foi tolerada)
 */
public record ClaudeResponse<T>(T entity, String model, long inputTokens, long outputTokens, long durationMs,
                                int maxOutputTokens, String stopReason, long contextBudgetTokens,
                                long contextPeakTokens, List<String> schemaViolations) {

    public ClaudeResponse {
        schemaViolations = schemaViolations != null ? List.copyOf(schemaViolations) : List.of();
    }

    public ClaudeResponse(T entity, String model, long inputTokens, long outputTokens, long durationMs) {
        this(entity, model, inputTokens, outputTokens, durationMs, 0, null, 0, 0, List.of());
    }
}
