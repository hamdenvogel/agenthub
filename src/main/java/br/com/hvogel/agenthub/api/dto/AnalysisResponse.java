package br.com.hvogel.agenthub.api.dto;

import java.time.Instant;
import java.util.List;

import br.com.hvogel.agenthub.analysis.AnalysisRun;
import br.com.hvogel.agenthub.analysis.AnalysisRun.ProgressEvent;
import br.com.hvogel.agenthub.analysis.AnalysisStatus;
import br.com.hvogel.agenthub.context.WorkspaceTarget;
import br.com.hvogel.agenthub.model.AgentRole;

/**
 * Situação de uma análise.
 */
public record AnalysisResponse(
        String analysisId,
        AnalysisStatus status,
        String request,
        List<AgentRole> requestedAgents,
        String projectName,
        String projectRoot,
        List<String> scope,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        String error,
        boolean reportAvailable,
        String reportFile,
        List<ProgressEvent> events) {

    public static AnalysisResponse from(AnalysisRun run) {
        return of(run, run.events());
    }

    public static AnalysisResponse summary(AnalysisRun run) {
        return of(run, List.of());
    }

    private static AnalysisResponse of(AnalysisRun run, List<ProgressEvent> events) {
        WorkspaceTarget target = run.target();
        return new AnalysisResponse(run.id(), run.status(), run.request(), run.requestedAgents(),
                target != null ? target.name() : null,
                target != null ? target.root().toString() : null,
                target != null ? target.includes() : List.of(),
                run.createdAt(), run.startedAt(), run.finishedAt(), run.error(), run.report() != null,
                run.reportPath(), events);
    }
}
