package br.com.hvogel.agenthub.claude;

import java.util.List;

import br.com.hvogel.agenthub.exception.ClaudeException;
import br.com.hvogel.agenthub.schema.JsonSchemaValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Única porta de acesso ao Claude. Agentes, Planner, Synthesizer e correção assistida não conhecem o
 * ChatClient: pedem uma resposta estruturada e recebem um record. Cada chamada é governada por três limites:
 *
 * <ul>
 *   <li><b>Context window</b> ("quanto cabe"): a parte fixa do prompt (system, user, formato de saída e tool
 *       schemas) é estimada e reservada no {@link ContextBudget} antes da chamada; as ferramentas somam seus
 *       resultados no mesmo orçamento durante o loop de tool calling;</li>
 *   <li><b>max_tokens</b> ("quanto pode responder"): enviado explicitamente; resposta cortada nesse limite é
 *       tratada como falha clara, não como JSON quebrado;</li>
 *   <li><b>JSON Schema</b> ("qual é o formato"): o schema do record vai no prompt e a resposta é validada
 *       contra ele antes da conversão. Os argumentos de ferramentas são validados no {@code GuardedToolCallback}.</li>
 * </ul>
 */
@Service
public class ClaudeService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeService.class);

    static final String MAX_TOKENS_STOP = "max_tokens";

    private final ChatClient chatClient;
    private final ClaudeProperties properties;

    public ClaudeService(ChatClient agentHubChatClient, ClaudeProperties properties) {
        this.chatClient = agentHubChatClient;
        this.properties = properties;
    }

    public <T> ClaudeResponse<T> structured(String systemPrompt, String userPrompt, List<ToolCallback> tools, Class<T> type) {
        return structured(systemPrompt, userPrompt, tools, type, CallLimits.defaults());
    }

    public <T> ClaudeResponse<T> structured(String systemPrompt, String userPrompt, List<ToolCallback> tools, Class<T> type,
                                            CallLimits limits) {
        long start = System.nanoTime();
        List<ToolCallback> callTools = tools != null ? tools : List.of();
        LenientOutputConverter<T> converter = new LenientOutputConverter<>(type);
        int maxOutputTokens = limits.maxOutputTokens() != null ? limits.maxOutputTokens() : properties.maxOutputTokens();
        ContextBudget context = limits.context();
        context.resolve(properties.contextBudgetTokens(), (long) properties.contextWindow() - maxOutputTokens);

        String user = userPrompt + "\n\n" + converter.getFormat();
        long fixed = TokenEstimator.estimate(systemPrompt) + TokenEstimator.estimate(user) + toolSchemaTokens(callTools);
        if (!context.reserve(fixed)) {
            throw new ClaudeException("Contexto inicial de ~%d tokens (prompts + %d tool schemas) excede o orçamento de %d tokens de %s."
                    .formatted(fixed, callTools.size(), context.limit(), type.getSimpleName()));
        }

        try {
            ChatClient.ChatClientRequestSpec request = chatClient.prompt()
                    .system(systemPrompt)
                    .user(user)
                    .options(ChatOptions.builder().maxTokens(maxOutputTokens));
            if (!callTools.isEmpty()) {
                request = request.tools(callTools.toArray());
            }
            ChatResponse chatResponse = request.call().chatResponse();
            Generation generation = chatResponse != null ? chatResponse.getResult() : null;
            String text = generation != null && generation.getOutput() != null ? generation.getOutput().getText() : null;
            String stopReason = generation != null && generation.getMetadata() != null ? generation.getMetadata().getFinishReason() : null;
            String model = chatResponse != null && chatResponse.getMetadata() != null ? chatResponse.getMetadata().getModel() : null;
            Usage usage = chatResponse != null && chatResponse.getMetadata() != null ? chatResponse.getMetadata().getUsage() : null;
            long inputTokens = usage != null && usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
            long outputTokens = usage != null && usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;

            if (isMaxTokens(stopReason)) {
                throw new ClaudeException("Resposta cortada em max_tokens=%d (%s): o JSON ficou incompleto. Responda de forma mais concisa."
                        .formatted(maxOutputTokens, type.getSimpleName()));
            }
            if (text == null || text.isBlank()) {
                throw new ClaudeException("Claude não retornou conteúdo estruturado para " + type.getSimpleName());
            }

            List<String> violations = JsonSchemaValidator.validate(converter.jsonSchema(), LenientOutputConverter.candidateJson(text));
            T entity;
            try {
                entity = converter.convert(text);
            } catch (RuntimeException e) {
                throw new ClaudeException("Resposta fora do JSON Schema de %s: %s".formatted(type.getSimpleName(),
                        violations.isEmpty() ? e.getMessage() : String.join("; ", violations)), e);
            }
            if (entity == null) {
                throw new ClaudeException("Claude não retornou conteúdo estruturado para " + type.getSimpleName());
            }

            long durationMs = (System.nanoTime() - start) / 1_000_000;
            ClaudeResponse<T> result = new ClaudeResponse<>(entity, model, inputTokens, outputTokens, durationMs,
                    maxOutputTokens, stopReason, context.limit(), context.used(), violations);
            if (!violations.isEmpty()) {
                log.warn("event=claude.schema_violation type={} count={} first=\"{}\"", type.getSimpleName(),
                        violations.size(), violations.getFirst());
            }
            log.info("event=claude.call type={} model={} tools={} inputTokens={} outputTokens={} maxTokens={} stopReason={} "
                            + "contextTokens={}/{} durationMs={}",
                    type.getSimpleName(), model, callTools.size(), inputTokens, outputTokens, maxOutputTokens, stopReason,
                    context.used(), context.limit(), durationMs);
            return result;
        } catch (ClaudeException e) {
            log.warn("event=claude.error type={} durationMs={} error={}", type.getSimpleName(),
                    (System.nanoTime() - start) / 1_000_000, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            log.warn("event=claude.error type={} durationMs={} error={}", type.getSimpleName(),
                    (System.nanoTime() - start) / 1_000_000, e.getMessage());
            throw new ClaudeException("Falha na chamada ao Claude: " + e.getMessage(), e);
        }
    }

    /** Tokens fixos por turno ocupados pelas definições das ferramentas (nome, descrição e JSON Schema de entrada). */
    static long toolSchemaTokens(List<ToolCallback> tools) {
        long total = 0;
        for (ToolCallback tool : tools) {
            ToolDefinition definition = tool.getToolDefinition();
            total += TokenEstimator.estimate(definition.name()) + TokenEstimator.estimate(definition.description())
                    + TokenEstimator.estimate(definition.inputSchema());
        }
        return total;
    }

    /** JSON Schema de Structured Output gerado para o record, idêntico ao enviado ao modelo. */
    public static String outputSchema(Class<?> type) {
        return new LenientOutputConverter<>(type).jsonSchema();
    }

    static boolean isMaxTokens(String stopReason) {
        return stopReason != null && stopReason.toLowerCase().contains(MAX_TOKENS_STOP);
    }
}
