package br.com.hvogel.agenthub.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import br.com.hvogel.agenthub.claude.CallLimits;
import br.com.hvogel.agenthub.claude.ClaudeResponse;
import br.com.hvogel.agenthub.claude.ClaudeService;
import br.com.hvogel.agenthub.config.AgentHubProperties;
import br.com.hvogel.agenthub.context.ProjectWorkspace;
import br.com.hvogel.agenthub.exception.AgentExecutionException;
import br.com.hvogel.agenthub.exception.ClaudeException;
import br.com.hvogel.agenthub.mcp.LocalToolSources;
import br.com.hvogel.agenthub.mcp.McpConnectionTools;
import br.com.hvogel.agenthub.mcp.McpToolRegistry;
import br.com.hvogel.agenthub.model.AgentAnalysis;
import br.com.hvogel.agenthub.model.AgentResult;
import br.com.hvogel.agenthub.model.AgentRole;
import br.com.hvogel.agenthub.model.AgentStatus;
import br.com.hvogel.agenthub.model.AgentTask;
import br.com.hvogel.agenthub.model.Severity;
import br.com.hvogel.agenthub.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.DefaultResourceLoader;

class BackendAgentTest {

    private final ClaudeService claude = mock(ClaudeService.class);
    private final AgentHubProperties props = Fixtures.properties();
    private final ToolCallback readFile = new ToolCallback() {
        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name("fs_read_file").description("ler").inputSchema("{}").build();
        }

        @Override
        public String call(String toolInput) {
            return "conteúdo";
        }
    };
    private final BackendAgent agent = new BackendAgent(new AgentDependencies(claude,
            new McpToolRegistry(props, new LocalToolSources(Map.of("filesystem", List.of(readFile))), mock(McpConnectionTools.class),
                    new ProjectWorkspace(props)),
            new AgentPromptLoader(new DefaultResourceLoader(), props), props));

    private final AgentTask task = new AgentTask(null, AgentRole.BACKEND, "Analisar services", List.of("transações"),
            List.of(), 2, "aprofunde os services");
    private final AgentExecutionContext context = new AgentExecutionContext("a1", "Analise o backend", "## Projeto\nbrief",
            List.of(Fixtures.success(AgentRole.ARCHITECTURE, 0.9, Fixtures.finding("Camadas misturadas", Severity.HIGH, "X.java"))));

    @SuppressWarnings("unchecked")
    @Test
    void executesWithSpecializedPromptAllowedToolsAndStructuredOutput() {
        AgentAnalysis analysis = Fixtures.analysis(0.85, Fixtures.finding("Transação ausente", Severity.HIGH, "ClienteService.java"));
        when(claude.structured(anyString(), anyString(), anyList(), eq(AgentAnalysis.class), any(CallLimits.class)))
                .thenReturn(new ClaudeResponse<>(analysis, "claude-test", 1200, 300, 50));

        AgentResult result = agent.execute(task, context);

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<ToolCallback>> tools = ArgumentCaptor.forClass(List.class);
        verify(claude).structured(system.capture(), user.capture(), tools.capture(), eq(AgentAnalysis.class), any(CallLimits.class));
        assertThat(system.getValue()).contains("Backend Agent").contains("Regras obrigatórias de saída");
        assertThat(user.getValue()).contains("Analisar services").contains("transações")
                .contains("aprofunde os services").contains("Camadas misturadas").contains("fs_read_file");
        assertThat(tools.getValue()).extracting(t -> t.getToolDefinition().name()).containsExactly("fs_read_file");

        assertThat(result.status()).isEqualTo(AgentStatus.SUCCESS);
        assertThat(result.agentName()).isEqualTo("Backend Agent");
        assertThat(result.severity()).isEqualTo(Severity.HIGH);
        assertThat(result.evidence()).containsExactly("ClienteService.java");
        assertThat(result.findings()).allMatch(f -> f.agent() == AgentRole.BACKEND);
        assertThat(result.metadata()).containsEntry("inputTokens", 1200L).containsEntry("model", "claude-test")
                .containsEntry("round", 2);
    }

    @Test
    void wrapsClaudeFailureForTheDelegator() {
        when(claude.structured(anyString(), anyString(), anyList(), eq(AgentAnalysis.class), any(CallLimits.class)))
                .thenThrow(new ClaudeException("rate limit"));
        assertThatThrownBy(() -> agent.execute(task, context))
                .isInstanceOf(AgentExecutionException.class).hasMessageContaining("rate limit");
    }

    @Test
    void confidenceIsClampedAndNullSafe() {
        when(claude.structured(anyString(), anyString(), anyList(), eq(AgentAnalysis.class), any(CallLimits.class)))
                .thenReturn(new ClaudeResponse<>(new AgentAnalysis("r", null, null, 7.0), "m", 0, 0, 0));
        assertThat(agent.execute(task, context).confidence()).isEqualTo(1.0);
    }
}
