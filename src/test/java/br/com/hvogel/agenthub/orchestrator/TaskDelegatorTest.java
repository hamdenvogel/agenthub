package br.com.hvogel.agenthub.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import br.com.hvogel.agenthub.agents.Agent;
import br.com.hvogel.agenthub.agents.AgentRegistry;
import br.com.hvogel.agenthub.config.AgentHubProperties;
import br.com.hvogel.agenthub.context.ContextSelector;
import br.com.hvogel.agenthub.model.AgentResult;
import br.com.hvogel.agenthub.model.AgentRole;
import br.com.hvogel.agenthub.model.AgentStatus;
import br.com.hvogel.agenthub.model.AgentTask;
import br.com.hvogel.agenthub.model.Severity;
import br.com.hvogel.agenthub.support.Fixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TaskDelegatorTest {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private TaskDelegator delegator(Duration agentTimeout, int maxAttempts, Agent... agents) {
        AgentHubProperties props = Fixtures.properties(".", Fixtures.orchestrator(2, agentTimeout, maxAttempts));
        return new TaskDelegator(new AgentRegistry(List.of(agents), props), new ContextSelector(), executor, props);
    }

    private static TaskDelegator.Round round(List<AgentTask> tasks, CancellationToken token) {
        return new TaskDelegator.Round("a1", "Analise", Fixtures.emptyProject(), tasks, Map.of(), token,
                Instant.now().plusSeconds(30), AnalysisProgress.NONE);
    }

    private static AgentTask task(AgentRole role, AgentRole... deps) {
        return AgentTask.initial(role, "objetivo", List.of(), List.of(deps));
    }

    @Test
    void independentTasksRunInParallel() {
        CountDownLatch bothStarted = new CountDownLatch(2);
        Agent backend = Fixtures.agent(AgentRole.BACKEND, (t, c) -> awaitPeer(bothStarted, t));
        Agent security = Fixtures.agent(AgentRole.SECURITY, (t, c) -> awaitPeer(bothStarted, t));

        Map<AgentRole, AgentResult> results = delegator(Duration.ofSeconds(5), 1, backend, security)
                .delegate(round(List.of(task(AgentRole.BACKEND), task(AgentRole.SECURITY)), new CancellationToken()));

        assertThat(results).containsOnlyKeys(AgentRole.BACKEND, AgentRole.SECURITY);
        assertThat(results.values()).allMatch(r -> r.status() == AgentStatus.SUCCESS);
    }

    @Test
    void dependentTaskWaitsAndReceivesPriorResult() {
        Map<AgentRole, List<AgentRole>> priorsSeen = new ConcurrentHashMap<>();
        Agent backend = Fixtures.agent(AgentRole.BACKEND, (t, c) ->
                Fixtures.success(AgentRole.BACKEND, 0.9, Fixtures.finding("Service grande", Severity.MEDIUM, "A.java")));
        Agent test = Fixtures.agent(AgentRole.TEST, (t, c) -> {
            priorsSeen.put(AgentRole.TEST, c.priorResults().stream().map(AgentResult::role).toList());
            return Fixtures.success(AgentRole.TEST, 0.9);
        });

        delegator(Duration.ofSeconds(5), 1, backend, test)
                .delegate(round(List.of(task(AgentRole.TEST, AgentRole.BACKEND), task(AgentRole.BACKEND)), new CancellationToken()));

        assertThat(priorsSeen.get(AgentRole.TEST)).containsExactly(AgentRole.BACKEND);
    }

    @Test
    void wavesRespectDependenciesAndBreakCycles() {
        List<List<AgentTask>> waves = TaskDelegator.waves(List.of(
                task(AgentRole.TEST, AgentRole.BACKEND), task(AgentRole.BACKEND), task(AgentRole.SECURITY)));
        assertThat(waves).hasSize(2);
        assertThat(waves.get(0)).extracting(AgentTask::role).containsExactlyInAnyOrder(AgentRole.BACKEND, AgentRole.SECURITY);
        assertThat(waves.get(1)).extracting(AgentTask::role).containsExactly(AgentRole.TEST);

        List<List<AgentTask>> cyclic = TaskDelegator.waves(List.of(
                task(AgentRole.BACKEND, AgentRole.TEST), task(AgentRole.TEST, AgentRole.BACKEND)));
        assertThat(cyclic).hasSize(1);
        assertThat(cyclic.get(0)).hasSize(2);
    }

    @Test
    void retriesAfterFailureAndSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        Agent flaky = Fixtures.agent(AgentRole.BACKEND, (t, c) -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("erro transitório");
            }
            return Fixtures.success(AgentRole.BACKEND, 0.8);
        });

        AgentResult result = delegator(Duration.ofSeconds(5), 2, flaky)
                .delegate(round(List.of(task(AgentRole.BACKEND)), new CancellationToken())).get(AgentRole.BACKEND);

        assertThat(result.status()).isEqualTo(AgentStatus.SUCCESS);
        assertThat(result.metadata()).containsEntry("attempts", 2);
        assertThat(calls).hasValue(2);
    }

    @Test
    void failureAfterAllAttemptsBecomesFailedResult() {
        Agent broken = Fixtures.agent(AgentRole.BACKEND, (t, c) -> {
            throw new IllegalStateException("sempre falha");
        });

        AgentResult result = delegator(Duration.ofSeconds(5), 2, broken)
                .delegate(round(List.of(task(AgentRole.BACKEND)), new CancellationToken())).get(AgentRole.BACKEND);

        assertThat(result.status()).isEqualTo(AgentStatus.FAILED);
        assertThat(result.error()).contains("sempre falha");
        assertThat(result.metadata()).containsEntry("attempts", 2);
    }

    @Test
    void slowAgentTimesOut() {
        Agent slow = Fixtures.agent(AgentRole.BACKEND, (t, c) -> {
            sleep(5_000);
            return Fixtures.success(AgentRole.BACKEND, 0.9);
        });

        AgentResult result = delegator(Duration.ofMillis(200), 1, slow)
                .delegate(round(List.of(task(AgentRole.BACKEND)), new CancellationToken())).get(AgentRole.BACKEND);

        assertThat(result.status()).isEqualTo(AgentStatus.TIMEOUT);
    }

    @Test
    void cancellationInterruptsRunningAgent() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Agent slow = Fixtures.agent(AgentRole.BACKEND, (t, c) -> {
            started.countDown();
            sleep(10_000);
            return Fixtures.success(AgentRole.BACKEND, 0.9);
        });
        CancellationToken token = new CancellationToken();
        TaskDelegator delegator = delegator(Duration.ofSeconds(30), 2, slow);

        var future = executor.submit(() -> delegator.delegate(round(List.of(task(AgentRole.BACKEND)), token)));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        token.cancel();

        AgentResult result = future.get(5, TimeUnit.SECONDS).get(AgentRole.BACKEND);
        assertThat(result.status()).isEqualTo(AgentStatus.CANCELLED);
    }

    @Test
    void missingAgentProducesFailedResult() {
        AgentResult result = delegator(Duration.ofSeconds(5), 1)
                .delegate(round(List.of(task(AgentRole.FRONTEND)), new CancellationToken())).get(AgentRole.FRONTEND);
        assertThat(result.status()).isEqualTo(AgentStatus.FAILED);
    }

    private static AgentResult awaitPeer(CountDownLatch latch, AgentTask task) {
        latch.countDown();
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("execução não foi paralela");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Fixtures.success(task.role(), 0.9);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrompido");
        }
    }
}
