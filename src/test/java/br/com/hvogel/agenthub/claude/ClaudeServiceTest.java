package br.com.hvogel.agenthub.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import br.com.hvogel.agenthub.exception.ClaudeException;
import br.com.hvogel.agenthub.model.AgentAnalysis;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class ClaudeServiceTest {

    private static final String VALID = """
            {"summary":"ok","confidence":0.8,"recommendations":[],
             "findings":[{"title":"t","description":"d","severity":"HIGH","category":"c","recommendation":"r",
                          "evidence":[{"file":"A.java","lines":"1","excerpt":"x"}]}]}""";

    private final ChatModel chatModel = mock(ChatModel.class);
    private final ClaudeService service;

    ClaudeServiceTest() {
        when(chatModel.getOptions()).thenReturn(ChatOptions.builder().model("claude-test").build());
        service = new ClaudeService(ChatClient.builder(chatModel).build(), new ClaudeProperties(200_000, 8_192, 120_000));
    }

    private void respond(String text, String stopReason) {
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage(text), ChatGenerationMetadata.builder().finishReason(stopReason).build())),
                ChatResponseMetadata.builder().model("claude-test").usage(new DefaultUsage(1_000, 200)).build());
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
    }

    @Test
    void sendsMaxTokensAndJsonSchemaAndReportsContextUsage() {
        respond("Segue a análise:\n" + VALID, "end_turn");
        ContextBudget context = new ContextBudget(50_000);

        ClaudeResponse<AgentAnalysis> result = service.structured("system", "user prompt", List.of(), AgentAnalysis.class,
                new CallLimits(4_096, context));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getOptions().getMaxTokens()).isEqualTo(4_096);
        assertThat(prompt.getValue().getUserMessage().getText())
                .contains("user prompt").contains("JSON Schema").contains("\"findings\"");

        assertThat(result.entity().findings()).hasSize(1);
        assertThat(result.maxOutputTokens()).isEqualTo(4_096);
        assertThat(result.stopReason()).isEqualTo("end_turn");
        assertThat(result.contextBudgetTokens()).isEqualTo(50_000);
        assertThat(result.contextPeakTokens()).isPositive().isEqualTo(context.fixed());
        assertThat(result.schemaViolations()).isEmpty();
        assertThat(result.inputTokens()).isEqualTo(1_000);
    }

    @Test
    void defaultsComeFromProperties() {
        respond(VALID, "end_turn");
        ClaudeResponse<AgentAnalysis> result = service.structured("s", "u", List.of(), AgentAnalysis.class);
        assertThat(result.maxOutputTokens()).isEqualTo(8_192);
        assertThat(result.contextBudgetTokens()).isEqualTo(120_000);
    }

    @Test
    void responseCutAtMaxTokensFailsWithClearMessage() {
        respond("{\"summary\":\"ok\",\"findings\":[{\"title\":", "max_tokens");
        assertThatThrownBy(() -> service.structured("s", "u", List.of(), AgentAnalysis.class))
                .isInstanceOf(ClaudeException.class).hasMessageContaining("max_tokens=8192");
    }

    @Test
    void schemaDivergencesAreReportedWhenConversionStillWorks() {
        respond("{\"summary\":\"ok\",\"confidence\":0.5}", "end_turn");
        ClaudeResponse<AgentAnalysis> result = service.structured("s", "u", List.of(), AgentAnalysis.class);
        assertThat(result.entity().summary()).isEqualTo("ok");
        assertThat(result.schemaViolations()).isNotEmpty().anySatisfy(v -> assertThat(v).contains("findings"));
    }

    @Test
    void unconvertibleOutputExplainsTheSchemaViolation() {
        respond("{\"summary\":\"ok\",\"confidence\":\"alta\",\"findings\":[],\"recommendations\":[]}", "end_turn");
        assertThatThrownBy(() -> service.structured("s", "u", List.of(), AgentAnalysis.class))
                .isInstanceOf(ClaudeException.class).hasMessageContaining("JSON Schema").hasMessageContaining("confidence");
    }

    @Test
    void promptThatDoesNotFitTheContextBudgetIsRejectedBeforeCallingTheModel() {
        ToolCallback tool = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("fs_read_file").description("x".repeat(3_500)).inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return "";
            }
        };
        assertThatThrownBy(() -> service.structured("s", "u", List.of(tool), AgentAnalysis.class,
                new CallLimits(null, new ContextBudget(1_000))))
                .isInstanceOf(ClaudeException.class).hasMessageContaining("excede o orçamento de 1000 tokens");
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void toolSchemasCountAsFixedContext() {
        ToolCallback tool = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("abcdefg").description("1234567").inputSchema("{\"type\":\"object\"}").build();
            }

            @Override
            public String call(String toolInput) {
                return "";
            }
        };
        assertThat(ClaudeService.toolSchemaTokens(List.of(tool))).isEqualTo(2 + 2 + 5);
        assertThat(ClaudeService.isMaxTokens("MAX_TOKENS")).isTrue();
        assertThat(ClaudeService.isMaxTokens("end_turn")).isFalse();
    }
}
