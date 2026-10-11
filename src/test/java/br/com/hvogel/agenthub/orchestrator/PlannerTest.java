package br.com.hvogel.agenthub.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;

import br.com.hvogel.agenthub.agents.AgentPromptLoader;
import br.com.hvogel.agenthub.agents.AgentRegistry;
import br.com.hvogel.agenthub.claude.ClaudeResponse;
import br.com.hvogel.agenthub.claude.ClaudeService;
import br.com.hvogel.agenthub.config.AgentHubProperties;
import br.com.hvogel.agenthub.context.ContextSelector;
import br.com.hvogel.agenthub.exception.ClaudeException;
import br.com.hvogel.agenthub.model.AgentRole;
import br.com.hvogel.agenthub.model.AgentTask;
import br.com.hvogel.agenthub.model.AnalysisPlan;
import br.com.hvogel.agenthub.orchestrator.Planner.PlanDraft;
import br.com.hvogel.agenthub.orchestrator.Planner.TaskDraft;
import br.com.hvogel.agenthub.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class PlannerTest {

    private final ClaudeService claude = mock(ClaudeService.class);

    private Planner planner(String mode) {
        AgentHubProperties base = Fixtures.properties();
        AgentHubProperties props = new AgentHubProperties(base.targetProject(), base.orchestrator(),
                new AgentHubProperties.Planner(mode), base.agents(), base.toolSources(), base.reports());
        AgentRegistry registry = new AgentRegistry(Arrays.stream(AgentRole.values())
                .map(r -> Fixtures.agent(r, (t, c) -> null)).toList(), props);
        return new Planner(registry, claude, new ContextSelector(), new AgentPromptLoader(new DefaultResourceLoader(), props), props);
    }

    @Test
    void fullScopeRequestSelectsAllAgents() {
        AnalysisPlan plan = planner("rules").plan("Analise a arquitetura completa do projeto", Fixtures.emptyProject(), List.of());
        assertThat(plan.roles()).containsExactlyInAnyOrder(AgentRole.values());
        assertThat(plan.source()).isEqualTo("rules");
        verifyNoInteractions(claude);
    }

    @Test
    void keywordsSelectSpecificAgentsAndTestDependsOnBackend() {
        AnalysisPlan plan = planner("rules").plan("Verifique segurança do JWT e os testes do backend",
                Fixtures.emptyProject(), List.of());
        assertThat(plan.roles()).contains(AgentRole.SECURITY, AgentRole.TEST, AgentRole.BACKEND)
                .doesNotContain(AgentRole.FRONTEND);
        AgentTask test = plan.tasks().stream().filter(t -> t.role() == AgentRole.TEST).findFirst().orElseThrow();
        assertThat(test.dependsOn()).containsExactly(AgentRole.BACKEND);
    }

    @Test
    void explicitAgentsOverridePlanning() {
        AnalysisPlan plan = planner("llm").plan("qualquer coisa", Fixtures.emptyProject(), List.of(AgentRole.DATABASE));
        assertThat(plan.roles()).containsExactly(AgentRole.DATABASE);
        verifyNoInteractions(claude);
    }

    @Test
    void llmPlanIsValidatedAndMapped() {
        PlanDraft draft = new PlanDraft("obj", "motivo", List.of(
                new TaskDraft("backend agent", "Analisar services", List.of("transações"), List.of("SECURITY", "BACKEND")),
                new TaskDraft("SECURITY", "Analisar JWT", null, null),
                new TaskDraft("INEXISTENTE", "x", null, null)));
        when(claude.structured(anyString(), anyString(), anyList(), eq(PlanDraft.class)))
                .thenReturn(new ClaudeResponse<>(draft, "claude", 100, 50, 10));

        AnalysisPlan plan = planner("llm").plan("Analise backend e segurança", Fixtures.emptyProject(), null);

        assertThat(plan.source()).isEqualTo("llm");
        assertThat(plan.roles()).containsExactly(AgentRole.BACKEND, AgentRole.SECURITY);
        AgentTask backend = plan.tasks().get(0);
        assertThat(backend.dependsOn()).containsExactly(AgentRole.SECURITY);
        assertThat(plan.inputTokens()).isEqualTo(100);
    }

    @Test
    void llmFailureFallsBackToRules() {
        when(claude.structured(anyString(), anyString(), anyList(), any())).thenThrow(new ClaudeException("indisponível"));
        AnalysisPlan plan = planner("llm").plan("Analise o frontend Angular", Fixtures.emptyProject(), null);
        assertThat(plan.source()).isEqualTo("rules");
        assertThat(plan.roles()).containsExactly(AgentRole.FRONTEND);
    }

    @Test
    void emptyLlmPlanFallsBackToRules() {
        when(claude.structured(anyString(), anyString(), anyList(), eq(PlanDraft.class)))
                .thenReturn(new ClaudeResponse<>(new PlanDraft("obj", "r", List.of()), "claude", 1, 1, 1));
        AnalysisPlan plan = planner("llm").plan("Analise o banco de dados", Fixtures.emptyProject(), null);
        assertThat(plan.source()).isEqualTo("rules");
        assertThat(plan.roles()).containsExactly(AgentRole.DATABASE);
    }
}
