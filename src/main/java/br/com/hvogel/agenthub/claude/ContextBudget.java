package br.com.hvogel.agenthub.claude;

/**
 * Context window de uma execução: "quanto cabe". Compartilhado entre o {@link ClaudeService}, que reserva a
 * parte fixa (system prompt, user prompt, formato de saída e tool schemas), e as ferramentas, que somam cada
 * resultado devolvido ao modelo. No loop de tool calling a conversa inteira é reenviada a cada turno, então o
 * tamanho do último turno é a parte fixa mais todos os resultados acumulados.
 *
 * <p>Quando um resultado não cabe, ele é cortado no que resta e o orçamento fica esgotado: as próximas
 * chamadas de ferramenta são recusadas e o modelo é instruído a concluir com o que já coletou.
 */
public final class ContextBudget {

    /** Abaixo disso não vale a pena devolver um pedaço de resultado. */
    static final long MIN_USEFUL_TOKENS = 200;

    private final Long requested;
    private long limit;
    private long fixed;
    private long tools;
    private boolean exhausted;

    /**
     * @param limitTokens orçamento de entrada; {@code null} usa o padrão de {@code agenthub.claude.context-budget-tokens}
     */
    public ContextBudget(Integer limitTokens) {
        this.requested = limitTokens != null && limitTokens > 0 ? Long.valueOf(limitTokens) : null;
        this.limit = requested != null ? requested : Long.MAX_VALUE;
    }

    /** Sem limite próprio: não limita até o {@link ClaudeService} resolver o padrão da chamada. */
    public static ContextBudget unlimited() {
        return new ContextBudget(null);
    }

    /**
     * Fixa o limite efetivo: o pedido (ou o padrão), nunca acima do que sobra da janela do modelo depois de
     * reservar {@code max_tokens} para a resposta.
     */
    synchronized void resolve(long defaultTokens, long windowMinusOutput) {
        long base = requested != null ? requested : defaultTokens;
        limit = Math.max(1, Math.min(base, windowMinusOutput));
    }

    /** Reserva a parte fixa do prompt; {@code false} se ela sozinha já não cabe. */
    synchronized boolean reserve(long fixedTokens) {
        fixed = fixedTokens;
        return fixed <= limit;
    }

    /**
     * Admite um texto que vai voltar ao modelo (resultado de ferramenta), cortando-o se necessário.
     *
     * @return o texto como deve ser devolvido ao modelo
     */
    public synchronized String admit(String text) {
        String value = text != null ? text : "";
        long tokens = TokenEstimator.estimate(value);
        long remaining = limit - used();
        if (tokens <= remaining) {
            tools += tokens;
            return value;
        }
        exhausted = true;
        if (remaining < MIN_USEFUL_TOKENS) {
            return exhaustedMessage();
        }
        String note = "\n... (resultado cortado: a context window desta execução está no limite de " + limit
                + " tokens. Não chame mais ferramentas; conclua com as evidências já coletadas.)";
        int keep = Math.max(0, TokenEstimator.chars(remaining) - note.length());
        String cut = value.substring(0, Math.min(keep, value.length())) + note;
        tools += TokenEstimator.estimate(cut);
        return cut;
    }

    public synchronized boolean exhausted() {
        return exhausted;
    }

    public String exhaustedMessage() {
        return "Context window desta execução esgotada (limite de " + limit + " tokens). "
                + "Não chame mais ferramentas: conclua com as evidências já coletadas e responda no formato solicitado.";
    }

    public synchronized long limit() {
        return limit == Long.MAX_VALUE ? 0 : limit;
    }

    public synchronized long fixed() {
        return fixed;
    }

    /** Tamanho estimado do maior turno (parte fixa + resultados acumulados). */
    public synchronized long used() {
        return fixed + tools;
    }
}
