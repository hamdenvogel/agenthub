package br.com.hvogel.agenthub.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import br.com.hvogel.agenthub.mcp.McpToolRegistry;
import br.com.hvogel.agenthub.mcp.ToolSourceSchemas;
import br.com.hvogel.agenthub.schema.SchemaCatalog.Catalog;
import br.com.hvogel.agenthub.schema.SchemaCatalog.StructuredOutputSchema;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SchemaCatalogTest {

    private final McpToolRegistry registry = mock(McpToolRegistry.class);
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void listsEveryStructuredOutputAndToolSource() {
        ToolSourceSchemas fs = new ToolSourceSchemas("filesystem", "local", null, true, true, "fs", List.of("backend"),
                List.of(new ToolSourceSchemas.ToolSchema("fs_read_file", "lê", "{\"type\":\"object\"}")), List.of());
        when(registry.schemas()).thenReturn(List.of(fs));

        Catalog catalog = new SchemaCatalog(registry).catalog();

        assertThat(catalog.structuredOutputs()).extracting(StructuredOutputSchema::name)
                .containsExactly("PlanDraft", "AgentAnalysis", "SynthesisDraft", "FixPlan", "PromptSuggestions");
        assertThat(catalog.structuredOutputs()).filteredOn(StructuredOutputSchema::feedsHub)
                .extracting(StructuredOutputSchema::name).containsExactly("AgentAnalysis");
        assertThat(catalog.toolInputs()).containsExactly(fs);

        JsonNode analysis = mapper.readTree(catalog.structuredOutputs().get(1).schema());
        assertThat(analysis.path("$schema").asString()).contains("2020-12");
        assertThat(analysis.path("properties").has("findings")).isTrue();
        assertThat(analysis.path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void schemasAreSerializedAsJsonObjectsNotStrings() {
        when(registry.schemas()).thenReturn(List.of(new ToolSourceSchemas("filesystem", "local", null, true, true, "fs",
                List.of(), List.of(new ToolSourceSchemas.ToolSchema("fs_read_file", "lê", "{\"type\":\"object\"}")), List.of())));

        JsonNode json = mapper.readTree(mapper.writeValueAsString(new SchemaCatalog(registry).catalog()));

        assertThat(json.path("structuredOutputs").get(0).path("schema").isObject()).isTrue();
        assertThat(json.path("toolInputs").get(0).path("tools").get(0).path("inputSchema").path("type").asString())
                .isEqualTo("object");
    }
}
