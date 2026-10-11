package br.com.hvogel.agenthub.claude;

/**
 * Limites de uma chamada: "quanto pode responder" ({@code max_tokens}) e "quanto cabe" (context window).
 * O mesmo {@link ContextBudget} deve ser passado às ferramentas da execução (via {@code ToolBudget}) para que
 * os resultados delas sejam contabilizados na mesma janela.
 *
 * @param maxOutputTokens {@code max_tokens}; {@code null} usa {@code agenthub.claude.max-output-tokens}
 * @param context         orçamento de contexto compartilhado com as ferramentas
 */
public record CallLimits(Integer maxOutputTokens, ContextBudget context) {

    public CallLimits {
        maxOutputTokens = maxOutputTokens != null && maxOutputTokens > 0 ? maxOutputTokens : null;
        context = context != null ? context : ContextBudget.unlimited();
    }

    /** Padrões globais, com um orçamento de contexto próprio. */
    public static CallLimits defaults() {
        return new CallLimits(null, null);
    }
}
