package br.com.hvogel.agenthub.agents;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import br.com.hvogel.agenthub.claude.CallLimits;
import br.com.hvogel.agenthub.claude.ClaudeResponse;
import br.com.hvogel.agenthub.claude.ClaudeService;
import br.com.hvogel.agenthub.claude.ContextBudget;
import br.com.hvogel.agenthub.config.AgentHubProperties;
import br.com.hvogel.agenthub.exception.AgentExecutionException;
import br.com.hvogel.agenthub.mcp.McpToolRegistry;
import br.com.hvogel.agenthub.mcp.ToolBudget;
import br.com.hvogel.agenthub.model.AgentAnalysis;
import br.com.hvogel.agenthub.model.AgentResult;
import br.com.hvogel.agenthub.model.AgentRole;
import br.com.hvogel.agenthub.model.AgentTask;
import br.com.hvogel.agenthub.model.Finding;
import org.springframework.ai.tool.ToolCallback;

/**
 * Comportamento comum dos especialistas: monta o prompt, resolve as ferramentas
 * permitidas ao papel e pede ao Claude um {@link AgentAnalysis}.
 * Subclasses definem apenas o papel e a descrição; o system prompt vem de recurso.
 */
public abstract class AbstractClaudeAgent implements Agent {

    static final String OUTPUT_RULES = """

            # Regras obrigatórias de saída
            - Responda em português do Brasil.
            - Baseie cada achado em evidência real lida com as ferramentas: arquivo (caminho relativo), linhas e trecho curto.
            - Não invente problemas, arquivos, versões ou métricas. Se não verificou, não afirme.
            - Prefira poucos achados relevantes e bem fundamentados a muitos achados genéricos.
            - Severidade: CRITICAL (risco grave/explorável), HIGH, MEDIUM, LOW, INFO (observação).
            - Use category "code-quality" para achados de qualidade de código/manutenibilidade.
            - confidence (0.0 a 1.0) deve refletir o quanto do escopo você realmente inspecionou.
            - Valores marcados como ***MASKED*** foram ocultados pelo Hub; trate-os como segredo presente no arquivo.
            - Ao terminar a investigação, pare de chamar ferramentas e responda somente no formato estruturado pedido.
            """;

    private final AgentRole role;
    private final String description;
    private final ClaudeService claude;
    private final McpToolRegistry toolRegistry;
    private final AgentPromptLoader promptLoader;
    private final AgentHubProperties properties;

    protected AbstractClaudeAgent(AgentRole role, String description, AgentDependencies deps) {
        this.role = role;
        this.description = description;
        this.claude = deps.claude();
        this.toolRegistry = deps.toolRegistry();
        this.promptLoader = deps.promptLoader();
        this.properties = deps.properties();
    }

    @Override
    public AgentRole role() {
        return role;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public String instructions() {
        return promptLoader.load(role) + OUTPUT_RULES;
    }

    @Override
    public List<String> toolSources() {
        return toolRegistry.sourcesFor(role);
    }

    @Override
    public AgentResult execute(AgentTask task, AgentExecutionContext context) {
        AgentHubProperties.AgentProperties config = properties.agent(role.id());
        CallLimits limits = new CallLimits(config.maxOutputTokens(), new ContextBudget(config.contextBudgetTokens()));
        ToolBudget budget = new ToolBudget(config.maxToolCalls(), limits.context());
        List<ToolCallback> tools = toolRegistry.toolsFor(role, budget);
        try {
            ClaudeResponse<AgentAnalysis> response = claude.structured(
                    instructions(), userPrompt(task, context, tools), tools, AgentAnalysis.class, limits);
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (response.model() != null) {
                metadata.put("model", response.model());
            }
            metadata.put("round", task.round());
            metadata.put("llmDurationMs", response.durationMs());
            metadata.put("inputTokens", response.inputTokens());
            metadata.put("outputTokens", response.outputTokens());
            metadata.put("maxOutputTokens", response.maxOutputTokens());
            if (response.stopReason() != null) {
                metadata.put("stopReason", response.stopReason());
            }
            metadata.put("contextBudgetTokens", response.contextBudgetTokens());
            metadata.put("contextPeakTokens", response.contextPeakTokens());
            metadata.put("contextExhausted", limits.context().exhausted());
            metadata.put("schemaViolations", response.schemaViolations());
            metadata.put("toolCalls", budget.used());
            metadata.put("toolInputRejected", budget.rejectedInputs());
            metadata.put("toolCallsBySource", budget.usedBySource());
            metadata.put("toolsAvailable", tools.stream().map(t -> t.getToolDefinition().name()).toList());
            return AgentResult.fromAnalysis(task, response.entity(), metadata);
        } catch (RuntimeException e) {
            throw new AgentExecutionException(name() + " falhou: " + e.getMessage(), e);
        }
    }

    String userPrompt(AgentTask task, AgentExecutionContext context, List<ToolCallback> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Solicitação do usuário\n").append(context.request()).append("\n\n");
        sb.append("# Sua tarefa (delegada pelo Hub)\n").append(task.objective()).append('\n');
        if (!task.focus().isEmpty()) {
            sb.append("Pontos de atenção:\n");
            task.focus().forEach(f -> sb.append("- ").append(f).append('\n'));
        }
        if (task.feedback() != null && !task.feedback().isBlank()) {
            sb.append("\n# Feedback do Hub sobre a rodada anterior (rodada ").append(task.round()).append(")\n")
                    .append(task.feedback()).append('\n');
        }
        if (!context.priorResults().isEmpty()) {
            sb.append("\n# Resultados de outros especialistas (use como ponto de partida, não repita)\n");
            context.priorResults().forEach(r -> sb.append(priorSummary(r)));
        }
        sb.append('\n').append(context.projectBrief()).append('\n');
        sb.append("# Ferramentas disponíveis\n");
        if (tools.isEmpty()) {
            sb.append("Nenhuma ferramenta disponível nesta execução. Analise apenas com o contexto acima e reduza a confidence.\n");
        } else {
            sb.append(tools.stream().map(t -> t.getToolDefinition().name()).collect(Collectors.joining(", ")))
                    .append("\nOrçamento: ").append(properties.agent(role.id()).maxToolCalls())
                    .append(" chamadas. Priorize os arquivos mais relevantes.\n");
        }
        return sb.toString();
    }

    private static String priorSummary(AgentResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(result.agentName()).append(" (").append(result.status()).append(")\n");
        if (result.summary() != null) {
            sb.append(result.summary()).append('\n');
        }
        result.findings().stream().limit(8).forEach(f -> sb.append("- [").append(f.severity()).append("] ")
                .append(f.title()).append(evidenceHint(f)).append('\n'));
        return sb.toString();
    }

    private static String evidenceHint(Finding finding) {
        return finding.evidence().stream().filter(e -> e.hasFile()).findFirst()
                .map(e -> " (" + e.file() + ")").orElse("");
    }
}
