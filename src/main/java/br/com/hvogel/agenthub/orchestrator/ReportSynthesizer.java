package br.com.hvogel.agenthub.orchestrator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import br.com.hvogel.agenthub.agents.AgentPromptLoader;
import br.com.hvogel.agenthub.claude.ClaudeResponse;
import br.com.hvogel.agenthub.claude.ClaudeService;
import br.com.hvogel.agenthub.context.ContextSelector;
import br.com.hvogel.agenthub.context.ProjectContext;
import br.com.hvogel.agenthub.model.AgentRole;
import br.com.hvogel.agenthub.model.AnalysisReport.ReportSection;
import br.com.hvogel.agenthub.model.AnalysisReport.RoadmapItem;
import br.com.hvogel.agenthub.model.Finding;
import br.com.hvogel.agenthub.model.Severity;
import br.com.hvogel.agenthub.orchestrator.ResultAggregator.Aggregation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * SYNTHESIZE: produz Executive Summary, narrativa de qualidade de código e roadmap a
 * partir dos achados consolidados (sem ferramentas: só o que os agentes evidenciaram).
 * Se o Claude falhar, gera uma síntese determinística por severidade.
 */
@Component
public class ReportSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(ReportSynthesizer.class);

    /** Síntese final. */
    public record Synthesis(
            String executiveSummary,
            String codeQuality,
            List<RoadmapItem> roadmap,
            List<String> keyRecommendations,
            String source,
            long inputTokens,
            long outputTokens) {
    }

    public record SynthesisDraft(String executiveSummary, String codeQuality, List<RoadmapItem> roadmap, List<String> keyRecommendations) {
    }

    private final ClaudeService claude;
    private final ContextSelector contextSelector;
    private final AgentPromptLoader promptLoader;

    public ReportSynthesizer(ClaudeService claude, ContextSelector contextSelector, AgentPromptLoader promptLoader) {
        this.claude = claude;
        this.contextSelector = contextSelector;
        this.promptLoader = promptLoader;
    }

    public Synthesis synthesize(String request, ProjectContext project, Aggregation aggregation, List<String> limitations) {
        if (aggregation.findings().isEmpty() && aggregation.sections().values().stream().noneMatch(ReportSection::analyzed)) {
            return rules(aggregation, limitations);
        }
        try {
            ClaudeResponse<SynthesisDraft> response = claude.structured(
                    promptLoader.read("classpath:prompts/hub/synthesizer.md"),
                    userPrompt(request, project, aggregation, limitations), List.of(), SynthesisDraft.class);
            SynthesisDraft draft = response.entity();
            Set<String> ids = aggregation.findings().stream().map(Finding::id).collect(Collectors.toSet());
            List<RoadmapItem> roadmap = draft.roadmap() == null ? List.of() : draft.roadmap().stream()
                    .filter(i -> i != null && i.action() != null)
                    .map(i -> new RoadmapItem(i.phase(), i.priority(), i.action(), i.rationale(),
                            i.relatedFindings().stream().filter(ids::contains).toList()))
                    .toList();
            return new Synthesis(draft.executiveSummary(), draft.codeQuality(), roadmap,
                    draft.keyRecommendations() != null ? draft.keyRecommendations() : List.of(),
                    "llm", response.inputTokens(), response.outputTokens());
        } catch (RuntimeException e) {
            log.warn("event=synthesizer.llm_failed fallback=rules error={}", e.getMessage());
            return rules(aggregation, limitations);
        }
    }

    private String userPrompt(String request, ProjectContext project, Aggregation aggregation, List<String> limitations) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Solicitação do usuário\n").append(request).append("\n\n");
        sb.append("# Projeto\n").append(contextSelector.overview(project)).append('\n');
        sb.append("# Resumos dos especialistas\n");
        for (Map.Entry<AgentRole, ReportSection> entry : aggregation.sections().entrySet()) {
            ReportSection section = entry.getValue();
            sb.append("## ").append(section.title()).append(section.analyzed() ? "" : " (não analisado)").append('\n')
                    .append(section.summary() != null ? section.summary() : "-").append('\n');
        }
        sb.append("\n# Achados consolidados (id | severidade | agente | categoria | título | arquivo)\n");
        for (Finding f : aggregation.findings()) {
            sb.append("- ").append(f.id()).append(" | ").append(f.severity()).append(" | ")
                    .append(f.agent() != null ? f.agent().reportSection() : "-").append(" | ")
                    .append(f.category()).append(" | ").append(f.title()).append(" | ")
                    .append(f.evidence().stream().filter(e -> e.hasFile()).map(e -> e.file()).findFirst().orElse("-"))
                    .append('\n');
        }
        if (!limitations.isEmpty()) {
            sb.append("\n# Limitações desta execução\n");
            limitations.forEach(l -> sb.append("- ").append(l).append('\n'));
        }
        return sb.toString();
    }

    Synthesis rules(Aggregation aggregation, List<String> limitations) {
        Map<Severity, Long> counts = aggregation.findingsBySeverity();
        StringBuilder summary = new StringBuilder();
        summary.append("Foram consolidados ").append(aggregation.findings().size()).append(" achados: ")
                .append(counts.entrySet().stream().filter(e -> e.getValue() > 0)
                        .map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", ")))
                .append(". ");
        aggregation.findings().stream()
                .filter(f -> f.severity().rank() >= Severity.HIGH.rank())
                .limit(5)
                .forEach(f -> summary.append("[").append(f.id()).append("] ").append(f.title()).append(". "));
        if (!limitations.isEmpty()) {
            summary.append("Limitações: ").append(String.join("; ", limitations)).append('.');
        }

        List<RoadmapItem> roadmap = new ArrayList<>();
        addPhase(roadmap, aggregation.findings(), "Fase 1 - Imediato", Severity.CRITICAL, Severity.HIGH);
        addPhase(roadmap, aggregation.findings(), "Fase 2 - Curto prazo", Severity.MEDIUM);
        addPhase(roadmap, aggregation.findings(), "Fase 3 - Melhoria contínua", Severity.LOW);
        return new Synthesis(summary.toString().trim(), null, roadmap,
                aggregation.recommendations().stream().limit(10).toList(), "rules", 0, 0);
    }

    private static void addPhase(List<RoadmapItem> roadmap, List<Finding> findings, String phase, Severity... severities) {
        Set<Severity> wanted = Set.of(severities);
        findings.stream()
                .filter(f -> wanted.contains(f.severity()))
                .limit(8)
                .forEach(f -> roadmap.add(new RoadmapItem(phase, f.severity(),
                        f.recommendation() != null ? f.recommendation() : "Tratar: " + f.title(),
                        f.title(), List.of(f.id()))));
    }
}
