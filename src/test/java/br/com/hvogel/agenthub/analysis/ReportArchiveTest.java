package br.com.hvogel.agenthub.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import br.com.hvogel.agenthub.config.AgentHubProperties;
import br.com.hvogel.agenthub.config.AgentHubProperties.Reports;
import br.com.hvogel.agenthub.exception.AnalysisNotFoundException;
import br.com.hvogel.agenthub.model.AgentRole;
import br.com.hvogel.agenthub.model.AgentStatus;
import br.com.hvogel.agenthub.model.AnalysisReport;
import br.com.hvogel.agenthub.model.AnalysisReport.AgentMetrics;
import br.com.hvogel.agenthub.model.AnalysisReport.ReportMetadata;
import br.com.hvogel.agenthub.model.Evidence;
import br.com.hvogel.agenthub.model.Finding;
import br.com.hvogel.agenthub.model.Severity;
import br.com.hvogel.agenthub.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ReportArchiveTest {

    @TempDir
    Path dir;

    private final JsonMapper mapper = JsonMapper.builder().build();

    private AgentHubProperties props() {
        AgentHubProperties base = Fixtures.properties();
        return new AgentHubProperties(base.targetProject(), base.orchestrator(), base.planner(), base.agents(),
                base.toolSources(), new Reports(true, dir.toString()));
    }

    private static AnalysisReport report(String analysisId) {
        Finding finding = new Finding("SEC-001", "Segredo versionado", "desc", Severity.CRITICAL, "secrets",
                List.of(new Evidence("app.properties", "3", "x")), "rec", AgentRole.SECURITY);
        AgentMetrics metrics = new AgentMetrics("security", "Security Agent", AgentStatus.SUCCESS, 1, 1, 1000, 900,
                100, 50, 4, Map.of("filesystem", 4L), 1, 0.8, "claude-test", null,
                8_192, "end_turn", 120_000, 5_000, false, 0, List.of());
        return new AnalysisReport(analysisId, "Analise a seguranca", "clientes", Instant.parse("2026-10-02T10:00:00Z"),
                "Resumo", null, null, null, null, null, null, null, List.of(finding), List.of("r1"), List.of(),
                new ReportMetadata(1, 1000, "rules", "rules", Map.of("security", AgentStatus.SUCCESS),
                        Map.of(Severity.CRITICAL, 1L), List.of(), Map.of("inputTokens", 100L), List.of(metrics),
                        "C:/projetos/clientes", List.of("clientes-api/src/main/java")), null);
    }

    @Test
    void listsAndLoadsReportsWrittenByReportWriter() {
        new ReportWriter(props(), new MarkdownReportRenderer(), mapper).write(report("abcdef12-0000-0000-0000-000000000000"));
        ReportArchive archive = new ReportArchive(props(), mapper);

        assertThat(archive.list()).singleElement().satisfies(a -> {
            assertThat(a.analysisId()).startsWith("abcdef12");
            assertThat(a.findings()).isEqualTo(1);
            assertThat(a.findingsBySeverity()).containsEntry(Severity.CRITICAL, 1L);
            assertThat(a.reportId()).endsWith("-abcdef12");
        });

        String id = archive.list().getFirst().reportId();
        AnalysisReport loaded = archive.load(id);
        assertThat(loaded.findings()).singleElement().satisfies(f -> assertThat(f.agent()).isEqualTo(AgentRole.SECURITY));
        assertThat(loaded.metadata().agentMetrics()).singleElement()
                .satisfies(m -> assertThat(m.toolCallsBySource()).containsEntry("filesystem", 4L));
    }

    @Test
    void rejectsIdsOutsideTheReportNamingScheme() throws Exception {
        Files.writeString(dir.resolve("other.json"), "{}");
        ReportArchive archive = new ReportArchive(props(), mapper);

        assertThat(archive.list()).isEmpty();
        assertThatThrownBy(() -> archive.load("../secret")).isInstanceOf(AnalysisNotFoundException.class);
        assertThatThrownBy(() -> archive.load("20261002-100000-deadbeef")).isInstanceOf(AnalysisNotFoundException.class);
    }
}
