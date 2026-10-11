package br.com.hvogel.agenthub.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import br.com.hvogel.agenthub.config.AgentHubProperties;
import br.com.hvogel.agenthub.exception.AnalysisNotFoundException;
import br.com.hvogel.agenthub.model.AgentStatus;
import br.com.hvogel.agenthub.model.AnalysisReport;
import br.com.hvogel.agenthub.model.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Histórico persistente: relatórios JSON gravados pelo {@link ReportWriter}, disponíveis após reinícios
 * (o {@link AnalysisRepository} guarda apenas as execuções em memória).
 */
@Component
public class ReportArchive {

    private static final Logger log = LoggerFactory.getLogger(ReportArchive.class);
    /** Só nomes gerados pelo ReportWriter: impede path traversal. */
    private static final Pattern REPORT_ID = Pattern.compile("\\d{8}-\\d{6}-[0-9a-f]{8}");

    private final AgentHubProperties.Reports config;
    private final JsonMapper jsonMapper;

    public ReportArchive(AgentHubProperties properties, JsonMapper jsonMapper) {
        this.config = properties.reports();
        this.jsonMapper = jsonMapper;
    }

    public record ArchivedReport(
            String reportId, String analysisId, String request, String projectName, Instant generatedAt,
            long durationMs, int findings, Map<Severity, Long> findingsBySeverity, Map<String, AgentStatus> agents,
            String projectRoot, List<String> scope) {
    }

    public List<ArchivedReport> list() {
        Path dir = directory();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .map(name -> name.substring(0, name.length() - ".json".length()))
                    .filter(id -> REPORT_ID.matcher(id).matches())
                    .map(this::summary)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(ArchivedReport::generatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
        } catch (IOException e) {
            log.warn("event=report.archive_list_failed error={}", e.getMessage());
            return List.of();
        }
    }

    public AnalysisReport load(String reportId) {
        if (reportId == null || !REPORT_ID.matcher(reportId).matches()) {
            throw new AnalysisNotFoundException(String.valueOf(reportId));
        }
        Path file = directory().resolve(reportId + ".json");
        if (!Files.isRegularFile(file)) {
            throw new AnalysisNotFoundException(reportId);
        }
        try {
            return jsonMapper.readValue(file.toFile(), AnalysisReport.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Relatório ilegível: " + reportId, e);
        }
    }

    private ArchivedReport summary(String reportId) {
        try {
            AnalysisReport r = load(reportId);
            var meta = r.metadata();
            return new ArchivedReport(reportId, r.analysisId(), r.request(), r.projectName(), r.generatedAt(),
                    meta != null ? meta.durationMs() : 0, r.findings().size(),
                    meta != null ? meta.findingsBySeverity() : Map.of(), meta != null ? meta.agents() : Map.of(),
                    meta != null ? meta.projectRoot() : null, meta != null ? meta.scope() : List.of());
        } catch (RuntimeException e) {
            log.warn("event=report.archive_skip reportId={} error={}", reportId, e.getMessage());
            return null;
        }
    }

    private Path directory() {
        return Path.of(config.outputDir()).toAbsolutePath().normalize();
    }
}
