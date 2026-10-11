package br.com.hvogel.agenthub.claude;

import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.converter.StructuredOutputConverter;

/**
 * Converte a resposta final do Claude no record esperado tolerando texto antes/depois do JSON
 * (ex.: "Com base na análise... {json}"). Sem isso, um preâmbulo descarta toda a investigação
 * do agente (tool calls e tokens) e força um retry completo.
 */
final class LenientOutputConverter<T> implements StructuredOutputConverter<T> {

    private final BeanOutputConverter<T> delegate;

    LenientOutputConverter(Class<T> type) {
        this.delegate = new BeanOutputConverter<>(type);
    }

    @Override
    public T convert(String text) {
        try {
            return delegate.convert(text);
        } catch (RuntimeException e) {
            String json = extractJsonObject(text);
            if (json == null || json.equals(text.strip())) {
                throw e;
            }
            return delegate.convert(json);
        }
    }

    @Override
    public String getFormat() {
        return delegate.getFormat();
    }

    /** JSON Schema gerado a partir do record (o mesmo enviado ao modelo em {@link #getFormat()}). */
    String jsonSchema() {
        return delegate.getJsonSchema();
    }

    /** O JSON que será convertido: o objeto contido no texto, ou o texto inteiro se não houver objeto. */
    static String candidateJson(String text) {
        String json = extractJsonObject(text);
        return json != null ? json : text;
    }

    /** Trecho do primeiro {@code {} ao último {@code }}, ou {@code null} se não houver objeto. */
    static String extractJsonObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : null;
    }
}
