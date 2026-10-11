package br.com.hvogel.agenthub.schema;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Valida JSON contra JSON Schema nos dois sentidos da conversa com o Claude:
 * <ul>
 *   <li><b>Tool Input Schema</b>: argumentos que o modelo envia a uma ferramenta (local ou MCP);</li>
 *   <li><b>Structured Output</b>: a resposta final, contra o schema gerado do record esperado.</li>
 * </ul>
 * Schema que não puder ser compilado (dialeto desconhecido, sintaxe inválida) não bloqueia nada: a validação
 * é pulada e registrada em log. A biblioteca (networknt) já vem com o MCP Java SDK.
 */
public final class JsonSchemaValidator {

    private static final Logger log = LoggerFactory.getLogger(JsonSchemaValidator.class);

    static final int MAX_VIOLATIONS = 5;
    static final int MAX_MESSAGE = 200;

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private static final Map<String, Optional<Schema>> CACHE = new ConcurrentHashMap<>();

    private JsonSchemaValidator() {
    }

    /**
     * @param schema JSON Schema (texto)
     * @param json   documento a validar; vazio é tratado como {@code {}}
     * @return violações (no máximo {@value #MAX_VIOLATIONS}); vazio = válido ou schema não verificável
     */
    public static List<String> validate(String schema, String json) {
        if (schema == null || schema.isBlank()) {
            return List.of();
        }
        Optional<Schema> compiled = CACHE.computeIfAbsent(schema, JsonSchemaValidator::compile);
        if (compiled.isEmpty()) {
            return List.of();
        }
        String document = json == null || json.isBlank() ? "{}" : json;
        try {
            List<Error> errors = compiled.get().validate(document, InputFormat.JSON);
            return errors.stream().limit(MAX_VIOLATIONS).map(JsonSchemaValidator::describe).toList();
        } catch (RuntimeException e) {
            return List.of("JSON inválido: " + abbreviate(e.getMessage()));
        }
    }

    private static Optional<Schema> compile(String schema) {
        try {
            return Optional.of(REGISTRY.getSchema(schema, InputFormat.JSON));
        } catch (RuntimeException e) {
            log.debug("event=schema.unverifiable reason={}", e.getMessage());
            return Optional.empty();
        }
    }

    private static String describe(Error error) {
        return abbreviate(error.toString());
    }

    private static String abbreviate(String message) {
        if (message == null) {
            return "erro de validação";
        }
        String single = message.replaceAll("\\s+", " ").strip();
        return single.length() > MAX_MESSAGE ? single.substring(0, MAX_MESSAGE) + "..." : single;
    }
}
