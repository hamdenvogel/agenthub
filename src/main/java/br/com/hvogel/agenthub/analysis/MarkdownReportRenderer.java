package br.com.hvogel.agenthub.analysis;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import br.com.hvogel.agenthub.model.AnalysisReport;
import br.com.hvogel.agenthub.model.AnalysisReport.AgentMetrics;
import br.com.hvogel.agenthub.model.AnalysisReport.ReportSection;
import br.com.hvogel.agenthub.model.AnalysisReport.RoadmapItem;
import br.com.hvogel.agenthub.model.Evidence;
import br.com.hvogel.agenthub.model.Finding;
import br.com.hvogel.agenthub.model.HubConsolidation;
import org.springframework.stereotype.Component;

/**
 * Renderiza o {@link AnalysisReport} nas 11 seções definidas para o relatório final.
 */
@Component
public class MarkdownReportRenderer {

    public String render(AnalysisReport r) {
        StringBuilder md = new StringBuilder();
        md.append("# Relatório de Análise - ").append(r.projectName()).append("\n\n");
        md.append("- **Análise:** `").append(r.analysisId()).append("`\n");
        md.append("- **Gerado em:** ").append(r.generatedAt()).append('\n');
        md.append("- **Solicitação:** ").append(r.request()).append("\n");
        if (r.metadata() != null && r.metadata().projectRoot() != null) {
            md.append("- **Diretório:** `").append(r.metadata().projectRoot()).append("`\n");
            md.append("- **Escopo:** ").append(r.metadata().scope().isEmpty()
                    ? "projeto inteiro"
                    : r.metadata().scope().stream().map(s -> "`" + s + "`").collect(Collectors.joining(", "))).append('\n');
        }
        if (r.metadata() != null) {
            md.append("- **Iterações:** ").append(r.metadata().iterations())
                    .append(" | **Duração:** ").append(r.metadata().durationMs() / 1000).append("s")
                    .append(" | **Planejamento:** ").append(r.metadata().plannerSource())
                    .append(" | **Síntese:** ").append(r.metadata().synthesisSource()).append('\n');
            md.append("- **Agentes:** ").append(r.metadata().agents().entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", "))).append('\n');
            md.append("- **Tokens:** ").append(r.metadata().usage()).append('\n');
        }
        md.append('\n');

        md.append("## 1. Executive Summary\n\n").append(orDash(r.executiveSummary())).append("\n\n");
        section(md, "2. Architecture", r.architecture());
        section(md, "3. Backend", r.backend());
        section(md, "4. Frontend", r.frontend());
        section(md, "5. Database", r.database());
        section(md, "6. Security", r.security());
        section(md, "7. Tests", r.tests());
        section(md, "8. Code Quality", r.codeQuality());

        md.append("## 9. Findings\n\n");
        if (r.findings().isEmpty()) {
            md.append("Nenhum achado consolidado.\n\n");
        }
        for (Finding f : r.findings()) {
            finding(md, f);
        }

        md.append("## 10. Recommendations\n\n");
        if (r.recommendations().isEmpty()) {
            md.append("-\n");
        }
        r.recommendations().forEach(rec -> md.append("- ").append(rec).append('\n'));
        md.append('\n');

        md.append("## 11. Suggested Roadmap\n\n");
        if (r.roadmap().isEmpty()) {
            md.append("-\n");
        }
        Map<String, List<RoadmapItem>> byPhase = r.roadmap().stream()
                .collect(Collectors.groupingBy(i -> orDash(i.phase()), java.util.LinkedHashMap::new, Collectors.toList()));
        byPhase.forEach((phase, items) -> {
            md.append("### ").append(phase).append("\n\n");
            items.forEach(i -> md.append("- **[").append(i.priority() != null ? i.priority() : "-").append("]** ")
                    .append(i.action())
                    .append(i.rationale() != null ? " - " + i.rationale() : "")
                    .append(i.relatedFindings().isEmpty() ? "" : " (" + String.join(", ", i.relatedFindings()) + ")")
                    .append('\n'));
            md.append('\n');
        });

        if (r.metadata() != null && !r.metadata().agentMetrics().isEmpty()) {
            md.append("## Desempenho por agente\n\n");
            md.append("| Agente | Status | Rodada | Tentativas | Duração (s) | Tokens entrada | Tokens saída | Tool calls | Achados | Confiança |"
                    + " Contexto (pico/orçamento) | max_tokens (parada) | JSON Schema |\n");
            md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
            for (AgentMetrics m : r.metadata().agentMetrics()) {
                md.append("| ").append(m.name()).append(" | ").append(m.status()).append(" | ").append(m.round())
                        .append(" | ").append(m.attempts()).append(" | ").append("%.1f".formatted(m.durationMs() / 1000.0))
                        .append(" | ").append(m.inputTokens()).append(" | ").append(m.outputTokens())
                        .append(" | ").append(m.toolCalls()).append(" | ").append(m.findings())
                        .append(" | ").append("%.2f".formatted(m.confidence()))
                        .append(" | ").append(contextCell(m))
                        .append(" | ").append(m.maxOutputTokens() > 0 ? m.maxOutputTokens() + " (" + orDash(m.stopReason()) + ")" : "-")
                        .append(" | ").append(schemaCell(m)).append(" |\n");
            }
            md.append('\n');
        }

        if (r.consolidation() != null) {
            consolidation(md, r.consolidation());
        }

        if (r.metadata() != null && !r.metadata().limitations().isEmpty()) {
            md.append("## Limitações desta execução\n\n");
            r.metadata().limitations().forEach(l -> md.append("- ").append(l).append('\n'));
            md.append('\n');
        }
        return md.toString();
    }

    private static void section(StringBuilder md, String title, ReportSection section) {
        md.append("## ").append(title).append("\n\n");
        if (section == null) {
            md.append("-\n\n");
            return;
        }
        if (section.status() != null) {
            md.append("_Status: ").append(section.status());
            if (section.confidence() > 0) {
                md.append(" | Confiança: ").append("%.2f".formatted(section.confidence()));
            }
            md.append("_\n\n");
        }
        md.append(orDash(section.summary())).append("\n\n");
        if (!section.findings().isEmpty()) {
            md.append("Achados: ").append(section.findings().stream()
                    .map(f -> "`" + f.id() + "` " + f.title() + " (" + f.severity() + ")")
                    .collect(Collectors.joining("; "))).append("\n\n");
        }
    }

    private static void consolidation(StringBuilder md, HubConsolidation c) {
        HubConsolidation.Totals t = c.totals();
        md.append("## Consolidação do Hub\n\n");
        md.append("- **Agentes:** ").append(t.agents()).append(" (").append(t.succeeded()).append(" concluídos, ")
                .append(t.failed()).append(" sem resultado)\n");
        md.append("- **Achados:** ").append(t.findingsReceived()).append(" recebidos, ").append(t.findingsKept())
                .append(" no relatório, ").append(t.findingsMerged()).append(" duplicados absorvidos\n");
        md.append("- **Recomendações:** ").append(t.recommendationsReceived()).append(" recebidas, ")
                .append(t.recommendationsKept()).append(" distintas\n");
        md.append("- **Arquivos citados como evidência:** ").append(t.evidenceFiles()).append("\n\n");
        md.append("| Agente | Status | Structured Output | Achados (recebidos/no relatório/duplicados) | Recomendações |"
                + " Arquivos | Confiança | Divergências de schema |\n");
        md.append("|---|---|---|---|---|---|---|---|\n");
        for (HubConsolidation.AgentOutput a : c.agents()) {
            md.append("| ").append(a.name()).append(" | ").append(a.status())
                    .append(" | ").append(orDash(a.outputSchema()))
                    .append(" | ").append(a.findingsReceived()).append('/').append(a.findingsKept()).append('/')
                    .append(a.findingsMerged())
                    .append(" | ").append(a.recommendations()).append(" | ").append(a.evidenceFiles())
                    .append(" | ").append("%.2f".formatted(a.confidence()))
                    .append(" | ").append(a.schemaViolations()).append(" |\n");
        }
        md.append('\n');
    }

    private static void finding(StringBuilder md, Finding f) {
        md.append("### ").append(f.id()).append(" - ").append(f.title()).append("\n\n");
        md.append("- **Severidade:** ").append(f.severity())
                .append(" | **Área:** ").append(f.agent() != null ? f.agent().reportSection() : "-")
                .append(" | **Categoria:** ").append(orDash(f.category())).append('\n');
        md.append("- **Descrição:** ").append(orDash(f.description())).append('\n');
        if (!f.evidence().isEmpty()) {
            md.append("- **Evidências:**\n");
            for (Evidence e : f.evidence()) {
                md.append("  - `").append(orDash(e.file())).append(e.lines() != null ? ":" + e.lines() : "").append('`');
                if (e.excerpt() != null && !e.excerpt().isBlank()) {
                    md.append(" - ").append(e.excerpt().replace('\n', ' ').trim());
                }
                md.append('\n');
            }
        }
        md.append("- **Recomendação:** ").append(orDash(f.recommendation())).append("\n\n");
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String contextCell(AgentMetrics m) {
        if (m.contextBudgetTokens() <= 0) {
            return "-";
        }
        return "~" + m.contextPeakTokens() + " / " + m.contextBudgetTokens() + (m.contextExhausted() ? " (esgotado)" : "");
    }

    private static String schemaCell(AgentMetrics m) {
        if (m.maxOutputTokens() <= 0) {
            return "-";
        }
        String schema = m.schemaViolations().isEmpty() ? "ok" : m.schemaViolations().size() + " divergência(s)";
        return m.toolInputRejected() > 0 ? schema + "; " + m.toolInputRejected() + " argumento(s) recusado(s)" : schema;
    }
}
