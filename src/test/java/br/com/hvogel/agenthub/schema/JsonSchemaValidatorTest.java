package br.com.hvogel.agenthub.schema;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.hvogel.agenthub.model.AgentAnalysis;
import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;

class JsonSchemaValidatorTest {

    private static final String SCHEMA = new BeanOutputConverter<>(AgentAnalysis.class).getJsonSchema();

    @Test
    void acceptsOutputThatFollowsTheRecordSchema() {
        String json = """
                {"summary":"ok","confidence":0.8,"recommendations":["r"],
                 "findings":[{"title":"t","description":"d","severity":"HIGH","category":"c","recommendation":"r",
                              "evidence":[{"file":"A.java","lines":"1-2","excerpt":"x"}]}]}""";
        assertThat(JsonSchemaValidator.validate(SCHEMA, json)).isEmpty();
    }

    @Test
    void reportsMissingFieldsWrongTypesAndInvalidEnums() {
        String json = """
                {"summary":"ok","confidence":"alta","recommendations":[],
                 "findings":[{"title":"t","severity":"URGENTE"}]}""";
        assertThat(JsonSchemaValidator.validate(SCHEMA, json))
                .isNotEmpty()
                .hasSizeLessThanOrEqualTo(JsonSchemaValidator.MAX_VIOLATIONS)
                .anySatisfy(v -> assertThat(v).contains("confidence"));
    }

    @Test
    void invalidJsonAndUnverifiableSchemasAreHandled() {
        assertThat(JsonSchemaValidator.validate(SCHEMA, "{nao é json")).singleElement()
                .satisfies(v -> assertThat(v).startsWith("JSON inválido"));
        assertThat(JsonSchemaValidator.validate("isto não é schema", "{}")).isEmpty();
        assertThat(JsonSchemaValidator.validate(null, "{}")).isEmpty();
    }

    @Test
    void emptyToolInputIsTreatedAsEmptyObject() {
        String schema = """
                {"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""";
        assertThat(JsonSchemaValidator.validate(schema, "")).isNotEmpty();
        assertThat(JsonSchemaValidator.validate("{\"type\":\"object\"}", "")).isEmpty();
    }
}
