package br.com.hvogel.agenthub.schema;

import java.time.Instant;
import java.util.List;

import br.com.hvogel.agenthub.claude.ClaudeService;
import br.com.hvogel.agenthub.mcp.McpToolRegistry;
import br.com.hvogel.agenthub.mcp.ToolSourceSchemas;
import br.com.hvogel.agenthub.model.AgentAnalysis;
import br.com.hvogel.agenthub.orchestrator.Planner.PlanDraft;
import br.com.hvogel.agenthub.orchestrator.PromptSuggester.PromptSuggestions;
import br.com.hvogel.agenthub.orchestrator.ReportSynthesizer.SynthesisDraft;
import br.com.hvogel.agenthub.remediation.FixPlan;
import com.fasterxml.jackson.annotation.JsonRawValue;
import org.springframework.stereotype.Component;

/**
 * Todos os JSON Schemas que a aplicação usa nas chamadas ao Claude: os de Structured Output (gerados dos records)
 * e os Tool Input Schemas de cada fonte (ferramentas locais e servidores MCP).
 */
@Component
public class SchemaCatalog {

    /**
     * @param name        record Java que gera o schema
     * @param usedBy      componente que pede esta saída ao Claude
     * @param purpose     o que a resposta carrega
     * @param feedsHub    a saída é entregue ao agrupador (Hub)
     * @param schema      JSON Schema (draft 2020-12), idêntico ao anexado ao prompt e validado na resposta
     */
    public record StructuredOutputSchema(String name, String usedBy, String purpose, boolean feedsHub,
                                         @JsonRawValue String schema) {
    }

    public record Catalog(Instant generatedAt, List<StructuredOutputSchema> structuredOutputs,
                          List<ToolSourceSchemas> toolInputs) {
    }

    private static final List<StructuredOutputSchema> STRUCTURED_OUTPUTS = List.of(
            output(PlanDraft.class, "Planner", "Plano: tarefas por agente, foco e dependências.", false),
            output(AgentAnalysis.class, "Agentes especialistas (AbstractClaudeAgent)",
                    "Resumo, achados com evidências, recomendações e confiança da área.", true),
            output(SynthesisDraft.class, "ReportSynthesizer",
                    "Sumário executivo, code quality, roadmap e recomendações-chave.", false),
            output(FixPlan.class, "RemediationService",
                    "Correção proposta: edições search/replace, arquivos novos, passos manuais e riscos.", false),
            output(PromptSuggestions.class, "PromptSuggester",
                    "Solicitações candidatas para a tela Nova análise, por agente.", false));

    private final McpToolRegistry toolRegistry;

    public SchemaCatalog(McpToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public Catalog catalog() {
        return new Catalog(Instant.now(), STRUCTURED_OUTPUTS, toolRegistry.schemas());
    }

    private static StructuredOutputSchema output(Class<?> type, String usedBy, String purpose, boolean feedsHub) {
        return new StructuredOutputSchema(type.getSimpleName(), usedBy, purpose, feedsHub, ClaudeService.outputSchema(type));
    }
}
