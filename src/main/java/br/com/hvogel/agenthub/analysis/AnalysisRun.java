package br.com.hvogel.agenthub.analysis;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import br.com.hvogel.agenthub.context.WorkspaceTarget;
import br.com.hvogel.agenthub.model.AgentRole;
import br.com.hvogel.agenthub.model.AnalysisReport;
import br.com.hvogel.agenthub.orchestrator.AnalysisProgress;
import br.com.hvogel.agenthub.orchestrator.CancellationToken;

/**
 * Estado mutável (thread-safe) de uma análise em execução.
 */
public class AnalysisRun implements AnalysisProgress {

    static final int MAX_EVENTS = 200;

    /**
     * @param at      instante
     * @param phase   fase do loop
     * @param message descrição
     */
    public record ProgressEvent(Instant at, String phase, String message) {
    }

    private final String id;
    private final String request;
    private final List<AgentRole> requestedAgents;
    private final WorkspaceTarget target;
    private final Instant createdAt = Instant.now();
    private final CancellationToken token = new CancellationToken();
    private final List<ProgressEvent> events = new CopyOnWriteArrayList<>();

    private volatile AnalysisStatus status = AnalysisStatus.PENDING;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile String error;
    private volatile AnalysisReport report;
    private volatile String reportPath;

    public AnalysisRun(String id, String request, List<AgentRole> requestedAgents) {
        this(id, request, requestedAgents, null);
    }

    public AnalysisRun(String id, String request, List<AgentRole> requestedAgents, WorkspaceTarget target) {
        this.id = id;
        this.request = request;
        this.requestedAgents = requestedAgents != null ? List.copyOf(requestedAgents) : List.of();
        this.target = target;
    }

    @Override
    public void event(String phase, String message) {
        if (events.size() < MAX_EVENTS) {
            events.add(new ProgressEvent(Instant.now(), phase, message));
        }
    }

    void markRunning() {
        status = AnalysisStatus.RUNNING;
        startedAt = Instant.now();
    }

    void complete(AnalysisReport finalReport) {
        report = finalReport;
        finish(AnalysisStatus.COMPLETED, null);
    }

    void fail(String message) {
        finish(AnalysisStatus.FAILED, message);
    }

    void cancelled() {
        finish(AnalysisStatus.CANCELLED, "Cancelada pelo usuário");
    }

    void reportPath(String path) {
        reportPath = path;
    }

    private void finish(AnalysisStatus finalStatus, String message) {
        status = finalStatus;
        error = message;
        finishedAt = Instant.now();
        event(finalStatus.name(), message != null ? message : "Análise concluída");
    }

    public String id() {
        return id;
    }

    public String request() {
        return request;
    }

    public List<AgentRole> requestedAgents() {
        return requestedAgents;
    }

    /** Projeto analisado nesta execução ({@code null} em análises criadas sem alvo). */
    public WorkspaceTarget target() {
        return target;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public CancellationToken token() {
        return token;
    }

    public List<ProgressEvent> events() {
        return List.copyOf(events);
    }

    public AnalysisStatus status() {
        return status;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public String error() {
        return error;
    }

    public AnalysisReport report() {
        return report;
    }

    public String reportPath() {
        return reportPath;
    }
}
