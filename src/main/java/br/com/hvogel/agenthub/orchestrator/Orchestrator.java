package br.com.hvogel.agenthub.orchestrator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import br.com.hvogel.agenthub.config.AgentHubProperties;
import br.com.hvogel.agenthub.context.ProjectContext;
import br.com.hvogel.agenthub.context.ProjectDiscoveryService;
import br.com.hvogel.agenthub.model.AgentResult;
import br.com.hvogel.agenthub.model.AgentRole;
import br.com.hvogel.agenthub.model.AgentStatus;
import br.com.hvogel.agenthub.model.AgentTask;
import br.com.hvogel.agenthub.model.AnalysisPlan;
import br.com.hvogel.agenthub.model.AnalysisReport;
import br.com.hvogel.agenthub.model.AnalysisReport.AgentMetrics;
import br.com.hvogel.agenthub.model.AnalysisReport.ReportMetadata;
import br.com.hvogel.agenthub.model.AnalysisReport.ReportSection;
import br.com.hvogel.agenthub.model.Evaluation;
import br.com.hvogel.agenthub.orchestrator.ReportSynthesizer.Synthesis;
import br.com.hvogel.agenthub.orchestrator.ResultAggregator.Aggregation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Hub único da arquitetura Hub-and-Spoke. Executa o agentic loop:
 *
 * <pre>
 * DISCOVERY -> PLAN -> [DELEGATE -> EXECUTE -> OBSERVE -> EVALUATE]* -> AGGREGATE -> SYNTHESIZE -> FINAL
 * </pre>
 *
 * O loop termina quando o Evaluator considera os resultados suficientes, ao atingir
 * {@code max-iterations}, ao esgotar {@code analysis-timeout} ou por cancelamento.
 * Cada papel (Planner, TaskDelegator, Evaluator, ResultAggregator, ReportSynthesizer)
 * é uma classe própria; o Orchestrator só coordena.
 */
@Service
public class Orchestrator {

    private static final Logger log = LoggerFactory.getLogger(Orchestrator.class);

    private final ProjectDiscoveryService discovery;
    private final Planner planner;
    private final TaskDelegator delegator;
    private final Evaluator evaluator;
    private final ResultAggregator aggregator;
    private final ReportSynthesizer synthesizer;
    private final AgentHubProperties.Orchestrator config;

    public Orchestrator(ProjectDiscoveryService discovery, Planner planner, TaskDelegator delegator, Evaluator evaluator,
                        ResultAggregator aggregator, ReportSynthesizer synthesizer, AgentHubProperties properties) {
        this.discovery = discovery;
        this.planner = planner;
        this.delegator = delegator;
        this.evaluator = evaluator;
        this.aggregator = aggregator;
        this.synthesizer = synthesizer;
        this.config = properties.orchestrator();
    }

    public AnalysisReport run(String analysisId, String request, List<AgentRole> requestedRoles,
                              CancellationToken token, AnalysisProgress progress) {
        long start = System.nanoTime();
        Instant deadline = Instant.now().plus(config.analysisTimeout());
        List<String> limitations = new ArrayList<>();

        progress.event("DISCOVERY", "Identificando stack e estrutura do projeto");
        ProjectContext project = discovery.discover();
        token.throwIfCancelled();

        progress.event("PLAN", "Planejando a análise");
        AnalysisPlan plan = planner.plan(request, project, requestedRoles);
        if (plan.tasks().isEmpty()) {
            throw new IllegalStateException("Nenhum agente habilitado para atender à solicitação.");
        }
        progress.event("PLAN", "Plano (" + plan.source() + "): " + plan.roles());
        log.info("event=orchestrator.plan analysisId={} source={} agents={}", analysisId, plan.source(), plan.roles());

        Map<AgentRole, AgentResult> results = new EnumMap<>(AgentRole.class);
        Map<AgentRole, Integer> followUpsUsed = new EnumMap<>(AgentRole.class);
        List<AgentTask> pending = plan.tasks();
        int iteration = 0;
        while (!pending.isEmpty()) {
            if (iteration >= config.maxIterations()) {
                limitations.add("Limite de " + config.maxIterations() + " iterações atingido; "
                        + pending.size() + " re-delegação(ões) não executada(s).");
                break;
            }
            if (Instant.now().isAfter(deadline)) {
                limitations.add("Tempo total da análise (" + config.analysisTimeout() + ") esgotado antes da rodada " + (iteration + 1) + ".");
                break;
            }
            token.throwIfCancelled();
            iteration++;
            log.info("event=orchestrator.round analysisId={} iteration={} agents={}", analysisId, iteration,
                    pending.stream().map(t -> t.role().id()).toList());

            Map<AgentRole, AgentResult> round = delegator.delegate(new TaskDelegator.Round(
                    analysisId, request, project, pending, results, token, deadline, progress));
            merge(results, round);
            token.throwIfCancelled();

            progress.event("EVALUATE", "Avaliando resultados da rodada " + iteration);
            Evaluation evaluation = evaluator.evaluate(pending, results, followUpsUsed);
            limitations.addAll(evaluation.notes());
            if (evaluation.sufficient()) {
                break;
            }
            evaluation.followUps().forEach(t -> followUpsUsed.merge(t.role(), 1, Integer::sum));
            pending = evaluation.followUps();
            progress.event("EVALUATE", "Insuficiente; re-delegando " + pending.stream().map(t -> t.role().displayName()).toList());
        }

        results.values().stream()
                .filter(r -> r.status() != AgentStatus.SUCCESS)
                .forEach(r -> limitations.add(r.agentName() + " não concluiu (" + r.status() + "): " + r.error()));
        results.values().stream()
                .filter(r -> r.status() == AgentStatus.SUCCESS)
                .forEach(r -> limitations.addAll(callLimitNotes(r)));

        progress.event("SYNTHESIZE", "Consolidando relatório final");
        Aggregation aggregation = aggregator.aggregate(results);
        Synthesis synthesis = synthesizer.synthesize(request, project, aggregation, limitations);

        long durationMs = (System.nanoTime() - start) / 1_000_000;
        AnalysisReport report = buildReport(analysisId, request, project, plan, results, aggregation, synthesis,
                limitations, iteration, durationMs);
        log.info("event=orchestrator.done analysisId={} iterations={} findings={} durationMs={}",
                analysisId, iteration, report.findings().size(), durationMs);
        return report;
    }

    /** Limites da chamada que afetaram um resultado concluído: context window esgotada e saída fora do JSON Schema. */
    static List<String> callLimitNotes(AgentResult r) {
        List<String> notes = new ArrayList<>();
        if (Boolean.TRUE.equals(r.metadata().get("contextExhausted"))) {
            notes.add(r.agentName() + ": context window de " + r.metadataLong("contextBudgetTokens")
                    + " tokens esgotada; resultados de ferramentas foram cortados e a análise pode estar incompleta.");
        }
        if (r.metadata().get("schemaViolations") instanceof List<?> violations && !violations.isEmpty()) {
            notes.add(r.agentName() + ": resposta com " + violations.size()
                    + " divergência(s) do JSON Schema (aceita após conversão tolerante): " + violations.getFirst());
        }
        return notes;
    }

    /** O resultado novo substitui o anterior se teve sucesso ou se o anterior também não teve. */
    static void merge(Map<AgentRole, AgentResult> results, Map<AgentRole, AgentResult> round) {
        round.forEach((role, fresh) -> results.merge(role, fresh,
                (previous, current) -> current.status() == AgentStatus.SUCCESS || previous.status() != AgentStatus.SUCCESS
                        ? current
                        : previous));
    }

    private AnalysisReport buildReport(String analysisId, String request, ProjectContext project, AnalysisPlan plan,
                                       Map<AgentRole, AgentResult> results, Aggregation aggregation, Synthesis synthesis,
                                       List<String> limitations, int iterations, long durationMs) {
        Map<String, AgentStatus> agentStatuses = new LinkedHashMap<>();
        results.forEach((role, r) -> agentStatuses.put(role.id(), r.status()));

        long input = plan.inputTokens() + synthesis.inputTokens()
                + results.values().stream().mapToLong(r -> r.metadataLong("inputTokens")).sum();
        long output = plan.outputTokens() + synthesis.outputTokens()
                + results.values().stream().mapToLong(r -> r.metadataLong("outputTokens")).sum();

        ReportSection quality = aggregation.codeQuality();
        ReportSection codeQuality = new ReportSection(quality.title(), quality.analyzed() || synthesis.codeQuality() != null,
                null, synthesis.codeQuality() != null ? synthesis.codeQuality() : quality.summary(), quality.findings(), 0.0);

        List<String> recommendations = !synthesis.keyRecommendations().isEmpty()
                ? synthesis.keyRecommendations()
                : aggregation.recommendations();

        Map<AgentRole, ReportSection> s = aggregation.sections();
        return new AnalysisReport(
                analysisId, request, project.name(), Instant.now(),
                synthesis.executiveSummary(),
                s.get(AgentRole.ARCHITECTURE), s.get(AgentRole.BACKEND), s.get(AgentRole.FRONTEND),
                s.get(AgentRole.DATABASE), s.get(AgentRole.SECURITY), s.get(AgentRole.TEST),
                codeQuality, aggregation.findings(), recommendations, synthesis.roadmap(),
                new ReportMetadata(iterations, durationMs, plan.source(), synthesis.source(), agentStatuses,
                        aggregation.findingsBySeverity(), List.copyOf(limitations),
                        Map.of("inputTokens", input, "outputTokens", output,
                                "plannerInputTokens", plan.inputTokens(), "plannerOutputTokens", plan.outputTokens(),
                                "synthesisInputTokens", synthesis.inputTokens(),
                                "synthesisOutputTokens", synthesis.outputTokens()),
                        results.values().stream().map(AgentMetrics::from).toList(),
                        project.root(), project.scope()),
                aggregation.consolidation());
    }
}
