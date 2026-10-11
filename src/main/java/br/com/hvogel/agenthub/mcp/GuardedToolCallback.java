package br.com.hvogel.agenthub.mcp;

import java.util.List;
import java.util.function.UnaryOperator;

import br.com.hvogel.agenthub.claude.ContextBudget;
import br.com.hvogel.agenthub.context.SecretMasker;
import br.com.hvogel.agenthub.exception.ToolAccessDeniedException;
import br.com.hvogel.agenthub.model.AgentRole;
import br.com.hvogel.agenthub.schema.JsonSchemaValidator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Decorator aplicado a toda ferramenta entregue a um agente, seja local ou MCP: orçamento de chamadas,
 * validação dos argumentos contra o Tool Input Schema, log estruturado (agente, fonte, ferramenta, duração),
 * mascaramento de segredos no retorno, contabilização na context window da execução e erros devolvidos ao
 * modelo como texto.
 */
public class GuardedToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(GuardedToolCallback.class);

    static final int MAX_RESULT_CHARS = 30_000;

    private final ToolCallback delegate;
    private final String source;
    private final String agent;
    private final ToolBudget budget;
    private final UnaryOperator<String> inputGuard;
    private final UnaryOperator<String> outputGuard;

    public GuardedToolCallback(ToolCallback delegate, String source, AgentRole role, ToolBudget budget) {
        this(delegate, source, role, budget, UnaryOperator.identity());
    }

    /**
     * @param inputGuard valida o JSON de entrada antes da chamada e devolve o JSON a executar (ex.: caminhos
     *                   reescritos como absolutos); lança {@link ToolAccessDeniedException} para negar
     */
    public GuardedToolCallback(ToolCallback delegate, String source, AgentRole role, ToolBudget budget,
                               UnaryOperator<String> inputGuard) {
        this(delegate, source, role.id(), budget, inputGuard);
    }

    /**
     * @param agent identificador de quem usa a ferramenta nos logs (ex.: {@code remediation})
     */
    public GuardedToolCallback(ToolCallback delegate, String source, String agent, ToolBudget budget,
                               UnaryOperator<String> inputGuard) {
        this(delegate, source, agent, budget, inputGuard, UnaryOperator.identity());
    }

    /**
     * @param outputGuard filtra o retorno da ferramenta antes do mascaramento de segredos (ex.: entradas de
     *                    diretórios excluídos numa listagem)
     */
    public GuardedToolCallback(ToolCallback delegate, String source, String agent, ToolBudget budget,
                               UnaryOperator<String> inputGuard, UnaryOperator<String> outputGuard) {
        this.delegate = delegate;
        this.source = source;
        this.agent = agent;
        this.budget = budget;
        this.inputGuard = inputGuard;
        this.outputGuard = outputGuard;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        String tool = getToolDefinition().name();
        ContextBudget context = budget.context();
        if (context.exhausted()) {
            log.warn("event=tool.context_exhausted agent={} source={} tool={} contextTokens={}/{}",
                    agent, source, tool, context.used(), context.limit());
            return context.exhaustedMessage();
        }
        if (!budget.tryAcquire(source)) {
            log.warn("event=tool.budget_exhausted agent={} source={} tool={} max={}", agent, source, tool, budget.maxCalls());
            return context.admit("Orçamento de " + budget.maxCalls() + " chamadas de ferramenta esgotado. "
                    + "Não chame mais ferramentas: conclua a análise com as evidências já coletadas e responda no formato solicitado.");
        }
        long start = System.nanoTime();
        List<String> violations = JsonSchemaValidator.validate(getToolDefinition().inputSchema(), toolInput);
        if (!violations.isEmpty()) {
            budget.rejectInput();
            log.warn("event=tool.call agent={} source={} tool={} status=invalid_input input={} violations=\"{}\"",
                    agent, source, tool, abbreviate(toolInput), String.join("; ", violations));
            return context.admit("ARGUMENTOS INVÁLIDOS para " + tool + " (não obedecem ao JSON Schema de entrada): "
                    + String.join("; ", violations) + ". Corrija os argumentos conforme o schema da ferramenta.");
        }
        try {
            String result = outputGuard.apply(delegate.call(inputGuard.apply(toolInput), toolContext));
            String safe = context.admit(truncate(SecretMasker.mask(result)));
            log.info("event=tool.call agent={} source={} tool={} status=ok durationMs={} input={} resultChars={} contextTokens={}/{}",
                    agent, source, tool, elapsedMs(start), abbreviate(toolInput), safe.length(), context.used(), context.limit());
            return safe;
        } catch (ToolAccessDeniedException e) {
            log.warn("event=tool.call agent={} source={} tool={} status=denied durationMs={} input={} reason={}",
                    agent, source, tool, elapsedMs(start), abbreviate(toolInput), e.getMessage());
            return context.admit("ACESSO NEGADO: " + e.getMessage());
        } catch (RuntimeException e) {
            log.warn("event=tool.call agent={} source={} tool={} status=error durationMs={} input={} error={}",
                    agent, source, tool, elapsedMs(start), abbreviate(toolInput), rootMessage(e));
            return context.admit("ERRO ao executar " + tool + ": " + rootMessage(e) + ". Ajuste os argumentos ou siga sem esta informação.");
        }
    }

    public String source() {
        return source;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > MAX_RESULT_CHARS
                ? value.substring(0, MAX_RESULT_CHARS) + "\n... (resultado truncado em " + MAX_RESULT_CHARS + " caracteres)"
                : value;
    }

    private static String abbreviate(String input) {
        if (input == null) {
            return "";
        }
        String masked = SecretMasker.mask(input).replaceAll("\\s+", " ");
        return masked.length() > 200 ? masked.substring(0, 200) + "..." : masked;
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }

    private static long elapsedMs(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
