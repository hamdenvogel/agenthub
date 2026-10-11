package br.com.hvogel.agenthub.claude;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limites de cada chamada ao Claude (prefixo {@code agenthub.claude}). Os agentes podem sobrescrever
 * {@code max-output-tokens} e {@code context-budget-tokens} em {@code agenthub.agents.<id>}.
 *
 * @param contextWindow       janela total do modelo em tokens (entrada + saída)
 * @param maxOutputTokens     {@code max_tokens} padrão: quanto o modelo pode gerar numa resposta
 * @param contextBudgetTokens orçamento de entrada padrão por execução: quanto do contexto pode ser ocupado
 *                            por prompts, tool schemas e resultados de ferramentas
 */
@ConfigurationProperties("agenthub.claude")
public record ClaudeProperties(int contextWindow, int maxOutputTokens, int contextBudgetTokens) {

    public ClaudeProperties {
        contextWindow = contextWindow > 0 ? contextWindow : 200_000;
        maxOutputTokens = maxOutputTokens > 0 ? maxOutputTokens : 8_192;
        contextBudgetTokens = contextBudgetTokens > 0 ? contextBudgetTokens : 120_000;
        if (maxOutputTokens >= contextWindow) {
            throw new IllegalArgumentException("agenthub.claude.max-output-tokens (" + maxOutputTokens
                    + ") precisa ser menor que context-window (" + contextWindow + ").");
        }
    }
}
