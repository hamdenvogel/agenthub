package br.com.hvogel.agenthub.claude;

/**
 * Estimativa local de tokens, usada para controlar a context window <em>antes</em> e <em>durante</em>
 * a chamada (a contagem exata só chega na resposta da API). Conservadora de propósito: código e
 * português ficam em torno de 3,5 caracteres por token no Claude, então a estimativa tende a sobrar.
 */
public final class TokenEstimator {

    static final double CHARS_PER_TOKEN = 3.5;

    private TokenEstimator() {
    }

    public static long estimate(String text) {
        return text == null || text.isEmpty() ? 0 : (long) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    /** Quantidade de caracteres que cabe em {@code tokens}. */
    public static int chars(long tokens) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, (long) Math.floor(tokens * CHARS_PER_TOKEN)));
    }
}
