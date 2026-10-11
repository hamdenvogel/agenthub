package br.com.hvogel.agenthub.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import br.com.hvogel.agenthub.agents.Agent;
import br.com.hvogel.agenthub.agents.AgentPromptLoader;
import br.com.hvogel.agenthub.agents.AgentRegistry;
import br.com.hvogel.agenthub.claude.ClaudeService;
import br.com.hvogel.agenthub.config.AgentHubProperties;
import br.com.hvogel.agenthub.context.ContextSelector;
import br.com.hvogel.agenthub.context.ProjectDiscoveryService;
import br.com.hvogel.agenthub.exception.ClaudeException;
import br.com.hvogel.agenthub.model.AgentResult;
import br.com.hvogel.agenthub.model.AgentRole;
import br.com.hvogel.agenthub.model.AgentStatus;
import br.com.hvogel.agenthub.model.AgentTask;
import br.com.hvogel.agenthub.model.AnalysisReport;
import br.com.hvogel.agenthub.model.Severity;
import br.com.hvogel.agenthub.support.Fixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class OrchestratorTest {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ClaudeService claude = mock(ClaudeService.class);
    private final ProjectDiscoveryService discovery = mock(ProjectDiscoveryService.class);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private Orchestrator orchestrator(int maxIterations, Agent... agents) {
        AgentHubProperties props = Fixtures.properties(".", Fixtures.orchestrator(maxIterations, Duration.ofSeconds(5), 1));
        AgentRegistry registry = new AgentRegistry(List.of(agents), props);
        ContextSelector selector = new ContextSelector();
        AgentPromptLoader prompts = new AgentPromptLoader(new DefaultResourceLoader(), props);
        when(discovery.discover()).thenReturn(Fixtures.emptyProject());
        when(claude.structured(anyString(), anyString(), anyList(), any())).thenThrow(new ClaudeException("sem LLM nos testes"));
        return new Orchestrator(discovery,
                new Planner(registry, claude, selector, prompts, props),
                new TaskDelegator(registry, selector, executor, props),
                new Evaluator(props), new ResultAggregator(),
                new ReportSynthesizer(claude, selector, prompts), props);
    }

    @Test
    void sufficientFirstRoundEndsLoopAndProducesReport() {
        AtomicInteger calls = new AtomicInteger();
        Agent backend = Fixtures.agent(AgentRole.BACKEND, (t, c) -> {
            calls.incrementAndGet();
            return Fixtures.success(AgentRole.BACKEND, 0.9, Fixtures.finding("Service com regra no controller", Severity.HIGH, "A.java"));
        });

        AnalysisReport report = orchestrator(3, backend).run("a1", "Analise o backend", null,
                new CancellationToken(), AnalysisProgress.NONE);

        assertThat(calls).hasValue(1);
        assertThat(report.metadata().iterations()).isEqualTo(1);
        assertThat(report.backend().analyzed()).isTrue();
        assertThat(report.findings()).singleElement().satisfies(f -> assertThat(f.id()).isEqualTo("BCK-001"));
        assertThat(report.metadata().synthesisSource()).isEqualTo("rules");
        assertThat(report.roadmap()).isNotEmpty();
        assertThat(report.metadata().limitations()).isEmpty();
        assertThat(report.metadata().agentMetrics()).singleElement().satisfies(m -> {
            assertThat(m.agent()).isEqualTo("backend");
            assertThat(m.status()).isEqualTo(AgentStatus.SUCCESS);
            assertThat(m.toolCalls()).isEqualTo(3);
            assertThat(m.findings()).isEqualTo(1);
            assertThat(m.attempts()).isEqualTo(1);
        });
    }

    @Test
    void insufficientResultIsRedelegatedThenAccepted() {
        AtomicInteger calls = new AtomicInteger();
        Agent backend = Fixtures.agent(AgentRole.BACKEND, (t, c) -> calls.incrementAndGet() == 1
                ? Fixtures.success(AgentRole.BACKEND, 0.1)
                : Fixtures.success(AgentRole.BACKEND, 0.9, Fixtures.finding("Achado", Severity.LOW, "B.java")));

        AnalysisReport report = orchestrator(3, backend).run("a1", "Analise o backend", null,
                new CancellationToken(), AnalysisProgress.NONE);

        assertThat(calls).hasValue(2);
        assertThat(report.metadata().iterations()).isEqualTo(2);
        assertThat(report.backend().confidence()).isEqualTo(0.9);
    }

    @Test
    void loopStopsAtMaxIterationsEvenIfStillInsufficient() {
        AtomicInteger calls = new AtomicInteger();
        Agent backend = Fixtures.agent(AgentRole.BACKEND, (t, c) -> {
            calls.incrementAndGet();
            return Fixtures.success(AgentRole.BACKEND, 0.1);
        });

        AnalysisReport report = orchestrator(1, backend).run("a1", "Analise o backend", null,
                new CancellationToken(), AnalysisProgress.NONE);

        assertThat(calls).hasValue(1);
        assertThat(report.metadata().iterations()).isEqualTo(1);
        assertThat(report.metadata().limitations()).anyMatch(l -> l.contains("Limite de 1 iterações"));
    }

    @Test
    void failedAgentIsReportedAsLimitationWithoutBreakingReport() {
        Agent backend = Fixtures.agent(AgentRole.BACKEND, (t, c) -> {
            throw new IllegalStateException("API fora do ar");
        });

        AnalysisReport report = orchestrator(2, backend).run("a1", "Analise o backend", null,
                new CancellationToken(), AnalysisProgress.NONE);

        assertThat(report.backend().analyzed()).isFalse();
        assertThat(report.backend().status()).isEqualTo(AgentStatus.FAILED);
        assertThat(report.metadata().limitations()).anyMatch(l -> l.contains("API fora do ar"));
    }

    @Test
    void cancelledBeforeStartThrows() {
        CancellationToken token = new CancellationToken();
        token.cancel();
        Orchestrator orchestrator = orchestrator(2, Fixtures.agent(AgentRole.BACKEND, (t, c) -> Fixtures.success(AgentRole.BACKEND, 0.9)));
        assertThatThrownBy(() -> orchestrator.run("a1", "Analise", null, token, AnalysisProgress.NONE))
                .isInstanceOf(CancellationException.class);
    }

    @Test
    void mergeKeepsPreviousSuccessWhenRetryFails() {
        Map<AgentRole, AgentResult> results = new EnumMap<>(AgentRole.class);
        AgentResult success = Fixtures.success(AgentRole.BACKEND, 0.4);
        results.put(AgentRole.BACKEND, success);
        AgentResult failed = AgentResult.failure(AgentTask.initial(AgentRole.BACKEND, "o", null, null),
                AgentStatus.TIMEOUT, "lento", Map.of());

        Orchestrator.merge(results, Map.of(AgentRole.BACKEND, failed));

        assertThat(results.get(AgentRole.BACKEND)).isSameAs(success);
    }

    @Test
    void contextExhaustionAndSchemaDivergencesBecomeLimitations() {
        AgentResult result = Fixtures.success(AgentRole.SECURITY, 0.8).withMetadata(Map.of(
                "contextExhausted", true, "contextBudgetTokens", 120_000L,
                "schemaViolations", List.of("$: required property 'recommendations' not found")));

        assertThat(Orchestrator.callLimitNotes(result))
                .anySatisfy(n -> assertThat(n).contains("context window de 120000 tokens esgotada"))
                .anySatisfy(n -> assertThat(n).contains("JSON Schema").contains("recommendations"));
        assertThat(Orchestrator.callLimitNotes(Fixtures.success(AgentRole.BACKEND, 0.8))).isEmpty();
    }
}
