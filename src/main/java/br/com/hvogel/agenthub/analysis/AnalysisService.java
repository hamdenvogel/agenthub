package br.com.hvogel.agenthub.analysis;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;

import br.com.hvogel.agenthub.context.ProjectWorkspace;
import br.com.hvogel.agenthub.context.WorkspaceTarget;
import br.com.hvogel.agenthub.exception.AnalysisNotFoundException;
import br.com.hvogel.agenthub.exception.InvalidTargetException;
import br.com.hvogel.agenthub.exception.ReportNotReadyException;
import br.com.hvogel.agenthub.model.AgentRole;
import br.com.hvogel.agenthub.model.AnalysisReport;
import br.com.hvogel.agenthub.orchestrator.Orchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Casos de uso da API: a análise é assíncrona porque leva minutos (vários agentes,
 * cada um com dezenas de chamadas de ferramenta). O POST devolve o id na hora e o
 * cliente acompanha o progresso por GET.
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private final Orchestrator orchestrator;
    private final AnalysisRepository repository;
    private final ReportWriter reportWriter;
    private final ExecutorService executor;
    private final ProjectWorkspace workspace;

    public AnalysisService(Orchestrator orchestrator, AnalysisRepository repository, ReportWriter reportWriter,
                           ExecutorService agentHubExecutor, ProjectWorkspace workspace) {
        this.orchestrator = orchestrator;
        this.repository = repository;
        this.reportWriter = reportWriter;
        this.executor = agentHubExecutor;
        this.workspace = workspace;
    }

    /** Registra a análise sobre o projeto ativo; a troca de projeto sincroniza no mesmo workspace. */
    public AnalysisRun submit(String request, List<AgentRole> agents) {
        AnalysisRun run;
        synchronized (workspace) {
            WorkspaceTarget target = workspace.target();
            if (!workspace.exists()) {
                throw new InvalidTargetException("Projeto ativo não encontrado em " + target.root()
                        + ". Selecione um projeto válido na tela Projetos.");
            }
            run = new AnalysisRun(UUID.randomUUID().toString(), request, agents, target);
            repository.save(run);
        }
        run.event("PENDING", "Análise recebida para " + run.target().name()
                + (run.target().scoped() ? " (escopo: " + String.join(", ", run.target().includes()) + ")" : ""));
        executor.submit(() -> execute(run));
        log.info("event=analysis.submitted analysisId={} agents={} project={} scope={}", run.id(),
                run.requestedAgents(), run.target().root(), run.target().includes());
        return run;
    }

    void execute(AnalysisRun run) {
        MDC.put("analysisId", run.id());
        try {
            run.markRunning();
            log.info("event=analysis.start analysisId={}", run.id());
            AnalysisReport report = orchestrator.run(run.id(), run.request(), run.requestedAgents(), run.token(), run);
            run.complete(report);
            reportWriter.write(report).ifPresent(run::reportPath);
            log.info("event=analysis.completed analysisId={} findings={}", run.id(), report.findings().size());
        } catch (CancellationException e) {
            run.cancelled();
            log.info("event=analysis.cancelled analysisId={}", run.id());
        } catch (RuntimeException e) {
            run.fail(e.getMessage());
            log.error("event=analysis.failed analysisId={} error={}", run.id(), e.getMessage(), e);
        } finally {
            MDC.remove("analysisId");
        }
    }

    public AnalysisRun get(String id) {
        return repository.find(id).orElseThrow(() -> new AnalysisNotFoundException(id));
    }

    public List<AnalysisRun> list() {
        return repository.findAll();
    }

    public AnalysisReport report(String id) {
        AnalysisRun run = get(id);
        if (run.report() == null) {
            throw new ReportNotReadyException(id, run.status().name());
        }
        return run.report();
    }

    public AnalysisRun cancel(String id) {
        AnalysisRun run = get(id);
        if (!run.status().isFinished()) {
            run.event("CANCEL", "Cancelamento solicitado");
            run.token().cancel();
            log.info("event=analysis.cancel_requested analysisId={}", id);
        }
        return run;
    }
}
