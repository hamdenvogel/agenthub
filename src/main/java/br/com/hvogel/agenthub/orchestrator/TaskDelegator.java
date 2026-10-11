package br.com.hvogel.agenthub.orchestrator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import br.com.hvogel.agenthub.agents.Agent;
import br.com.hvogel.agenthub.agents.AgentExecutionContext;
import br.com.hvogel.agenthub.agents.AgentRegistry;
import br.com.hvogel.agenthub.config.AgentHubProperties;
import br.com.hvogel.agenthub.context.ContextSelector;
import br.com.hvogel.agenthub.context.ProjectContext;
import br.com.hvogel.agenthub.model.AgentResult;
import br.com.hvogel.agenthub.model.AgentRole;
import br.com.hvogel.agenthub.model.AgentStatus;
import br.com.hvogel.agenthub.model.AgentTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * DELEGATE / EXECUTE / OBSERVE: entrega cada tarefa ao agente com o contexto do seu papel.
 *
 * <ul>
 *   <li>Tarefas independentes rodam em paralelo; dependentes esperam a "onda" anterior.</li>
 *   <li>Cada execução tem timeout, retry com backoff e respeita o cancelamento.</li>
 *   <li>O paralelismo é limitado globalmente (todas as análises) para proteger o rate limit da API.</li>
 *   <li>Falhas nunca propagam: viram {@link AgentResult} com status FAILED/TIMEOUT/CANCELLED.</li>
 * </ul>
 */
@Component
public class TaskDelegator {

    private static final Logger log = LoggerFactory.getLogger(TaskDelegator.class);

    private final AgentRegistry agents;
    private final ContextSelector contextSelector;
    private final ExecutorService executor;
    private final AgentHubProperties.Orchestrator config;
    private final Semaphore permits;

    public TaskDelegator(AgentRegistry agents, ContextSelector contextSelector, ExecutorService agentHubExecutor,
                         AgentHubProperties properties) {
        this.agents = agents;
        this.contextSelector = contextSelector;
        this.executor = agentHubExecutor;
        this.config = properties.orchestrator();
        this.permits = new Semaphore(config.maxParallelAgents(), true);
    }

    /** Parâmetros de uma rodada de delegação. */
    public record Round(
            String analysisId,
            String request,
            ProjectContext project,
            List<AgentTask> tasks,
            Map<AgentRole, AgentResult> previousResults,
            CancellationToken token,
            Instant deadline,
            AnalysisProgress progress) {
    }

    public Map<AgentRole, AgentResult> delegate(Round round) {
        Map<AgentRole, AgentResult> results = new EnumMap<>(AgentRole.class);
        for (List<AgentTask> wave : waves(round.tasks())) {
            if (round.token().isCancelled()) {
                wave.forEach(t -> results.put(t.role(), AgentResult.failure(t, AgentStatus.CANCELLED, "Análise cancelada", Map.of())));
                continue;
            }
            round.progress().event("DELEGATE", "Executando " + wave.stream().map(t -> t.role().displayName()).toList());
            Map<AgentRole, AgentResult> known = new EnumMap<>(AgentRole.class);
            known.putAll(round.previousResults());
            known.putAll(results);

            List<Future<AgentResult>> futures = new ArrayList<>();
            for (AgentTask task : wave) {
                List<AgentResult> prior = task.dependsOn().stream().map(known::get).filter(r -> r != null).toList();
                futures.add(executor.submit(withMdc(round.analysisId(), task.role(),
                        () -> executeWithRetry(task, prior, round))));
            }
            for (int i = 0; i < wave.size(); i++) {
                AgentTask task = wave.get(i);
                AgentResult result = await(futures.get(i), task);
                results.put(task.role(), result);
                round.progress().event("OBSERVE", task.role().displayName() + ": " + result.status()
                        + " (" + result.findings().size() + " achados)");
            }
        }
        return results;
    }

    AgentResult executeWithRetry(AgentTask task, List<AgentResult> prior, Round round) {
        Agent agent = agents.find(task.role()).orElse(null);
        if (agent == null) {
            return AgentResult.failure(task, AgentStatus.FAILED, "Agente não habilitado: " + task.role(), Map.of());
        }
        AgentExecutionContext context = new AgentExecutionContext(round.analysisId(), round.request(),
                contextSelector.briefFor(task.role(), round.project()), prior);
        long start = System.nanoTime();
        AgentStatus lastStatus = AgentStatus.FAILED;
        String lastError = null;
        int attempt = 0;
        while (attempt < config.maxAttempts()) {
            attempt++;
            if (round.token().isCancelled()) {
                return finish(AgentResult.failure(task, AgentStatus.CANCELLED, "Análise cancelada", Map.of()), attempt, start);
            }
            Duration remaining = Duration.between(Instant.now(), round.deadline());
            if (remaining.isNegative() || remaining.isZero()) {
                return finish(AgentResult.failure(task, AgentStatus.TIMEOUT, "Prazo total da análise esgotado", Map.of()), attempt, start);
            }
            Duration timeout = remaining.compareTo(config.agentTimeout()) < 0 ? remaining : config.agentTimeout();
            log.info("event=agent.start agent={} task={} round={} attempt={} timeoutMs={}",
                    task.role().id(), task.id(), task.round(), attempt, timeout.toMillis());

            Future<AgentResult> future = null;
            boolean acquired = false;
            try {
                permits.acquire();
                acquired = true;
                future = executor.submit(withMdc(round.analysisId(), task.role(), () -> agent.execute(task, context)));
                round.token().register(future);
                AgentResult result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                log.info("event=agent.end agent={} status={} findings={} confidence={} attempt={} durationMs={}",
                        task.role().id(), result.status(), result.findings().size(), result.confidence(), attempt, elapsedMs(start));
                return finish(result, attempt, start);
            } catch (TimeoutException e) {
                future.cancel(true);
                lastStatus = AgentStatus.TIMEOUT;
                lastError = "Tempo limite de " + timeout.toSeconds() + "s excedido";
            } catch (ExecutionException e) {
                lastStatus = AgentStatus.FAILED;
                lastError = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            } catch (CancellationException e) {
                return finish(AgentResult.failure(task, AgentStatus.CANCELLED, "Análise cancelada", Map.of()), attempt, start);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (future != null) {
                    future.cancel(true);
                }
                return finish(AgentResult.failure(task, AgentStatus.CANCELLED, "Execução interrompida", Map.of()), attempt, start);
            } finally {
                if (future != null) {
                    round.token().unregister(future);
                }
                if (acquired) {
                    permits.release();
                }
            }
            log.warn("event=agent.retry agent={} attempt={} maxAttempts={} status={} error={}",
                    task.role().id(), attempt, config.maxAttempts(), lastStatus, lastError);
            if (attempt < config.maxAttempts() && !sleepBackoff(attempt)) {
                return finish(AgentResult.failure(task, AgentStatus.CANCELLED, "Execução interrompida", Map.of()), attempt, start);
            }
        }
        log.warn("event=agent.failed agent={} status={} attempts={} error={}", task.role().id(), lastStatus, attempt, lastError);
        return finish(AgentResult.failure(task, lastStatus, lastError, Map.of()), attempt, start);
    }

    /**
     * Ordena as tarefas em ondas: uma tarefa só entra quando todas as suas dependências
     * (presentes nesta rodada) já estão em ondas anteriores. Ciclos são quebrados com aviso.
     */
    static List<List<AgentTask>> waves(List<AgentTask> tasks) {
        Set<AgentRole> inRound = new HashSet<>();
        tasks.forEach(t -> inRound.add(t.role()));
        List<AgentTask> pending = new ArrayList<>(tasks);
        Set<AgentRole> done = new HashSet<>();
        List<List<AgentTask>> waves = new ArrayList<>();
        while (!pending.isEmpty()) {
            List<AgentTask> wave = pending.stream()
                    .filter(t -> t.dependsOn().stream().filter(inRound::contains).allMatch(done::contains))
                    .toList();
            if (wave.isEmpty()) {
                log.warn("event=delegator.cycle tasks={}", pending.stream().map(t -> t.role().id()).toList());
                wave = List.copyOf(pending);
            }
            waves.add(wave);
            pending.removeAll(wave);
            wave.forEach(t -> done.add(t.role()));
        }
        return waves;
    }

    private AgentResult await(Future<AgentResult> future, AgentTask task) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return AgentResult.failure(task, AgentStatus.CANCELLED, "Execução interrompida", Map.of());
        } catch (ExecutionException | CancellationException e) {
            return AgentResult.failure(task, AgentStatus.FAILED, String.valueOf(e.getMessage()), Map.of());
        }
    }

    private boolean sleepBackoff(int attempt) {
        try {
            Thread.sleep(config.retryBackoff().multipliedBy(attempt));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static AgentResult finish(AgentResult result, int attempts, long start) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("attempts", attempts);
        meta.put("durationMs", elapsedMs(start));
        return result.withMetadata(meta);
    }

    private static <T> java.util.concurrent.Callable<T> withMdc(String analysisId, AgentRole role, java.util.concurrent.Callable<T> task) {
        return () -> {
            MDC.put("analysisId", analysisId);
            MDC.put("agent", role.id());
            try {
                return task.call();
            } finally {
                MDC.remove("agent");
                MDC.remove("analysisId");
            }
        };
    }

    private static long elapsedMs(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
