package br.com.hvogel.agenthub.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;

import br.com.hvogel.agenthub.context.ProjectWorkspace;
import br.com.hvogel.agenthub.context.WorkspaceTarget;
import br.com.hvogel.agenthub.exception.InvalidTargetException;
import br.com.hvogel.agenthub.exception.ReportNotReadyException;
import br.com.hvogel.agenthub.model.AnalysisReport;
import br.com.hvogel.agenthub.orchestrator.Orchestrator;
import br.com.hvogel.agenthub.support.Fixtures;
import org.junit.jupiter.api.Test;

class AnalysisServiceTest {

    private final Orchestrator orchestrator = mock(Orchestrator.class);
    private final ReportWriter writer = mock(ReportWriter.class);
    private final ProjectWorkspace workspace = new ProjectWorkspace(Fixtures.properties());
    private final AnalysisService service = new AnalysisService(orchestrator, new AnalysisRepository(), writer,
            mock(ExecutorService.class), workspace);

    private static AnalysisReport report(String id) {
        return new AnalysisReport(id, "r", "clientes", Instant.now(), "ok", null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), null, null);
    }

    @Test
    void completedRunExposesReportAndFile() {
        AnalysisRun run = service.submit("Analise", null);
        when(orchestrator.run(anyString(), anyString(), any(), any(), any())).thenReturn(report(run.id()));
        when(writer.write(any())).thenReturn(Optional.of("reports/x.md"));

        service.execute(run);

        assertThat(run.status()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(service.report(run.id()).executiveSummary()).isEqualTo("ok");
        assertThat(run.reportPath()).isEqualTo("reports/x.md");
        assertThat(run.events()).extracting(AnalysisRun.ProgressEvent::phase).contains("PENDING", "COMPLETED");
        assertThat(run.target().root()).isEqualTo(workspace.root());
    }

    @Test
    void submitIsRejectedWhenActiveProjectIsMissing() {
        workspace.switchTo(new WorkspaceTarget("x", "sumiu", Path.of("nao-existe-" + System.nanoTime()), List.of()));

        assertThatThrownBy(() -> service.submit("Analise", null))
                .isInstanceOf(InvalidTargetException.class)
                .hasMessageContaining("Projeto ativo não encontrado");
        assertThat(service.list()).isEmpty();
    }

    @Test
    void failureAndCancellationAreRecorded() {
        AnalysisRun failed = service.submit("Analise", null);
        when(orchestrator.run(anyString(), anyString(), any(), any(), any())).thenThrow(new IllegalStateException("sem agentes"));
        service.execute(failed);
        assertThat(failed.status()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(failed.error()).isEqualTo("sem agentes");
        assertThatThrownBy(() -> service.report(failed.id())).isInstanceOf(ReportNotReadyException.class);

        AnalysisRun cancelled = service.submit("Analise", null);
        service.cancel(cancelled.id());
        assertThat(cancelled.token().isCancelled()).isTrue();
        doThrow(new CancellationException()).when(orchestrator).run(anyString(), anyString(), any(), any(), any());
        service.execute(cancelled);
        assertThat(cancelled.status()).isEqualTo(AnalysisStatus.CANCELLED);
    }
}
