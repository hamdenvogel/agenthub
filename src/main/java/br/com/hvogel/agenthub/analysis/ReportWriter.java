package br.com.hvogel.agenthub.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import br.com.hvogel.agenthub.config.AgentHubProperties;
import br.com.hvogel.agenthub.model.AnalysisReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Grava cada relatório concluído em {@code <output-dir>/<data>-<id>.md} e {@code .json}.
 * Escreve somente no diretório do AgentHub, nunca no projeto analisado.
 */
@Component
public class ReportWriter {

    private static final Logger log = LoggerFactory.getLogger(ReportWriter.class);
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final AgentHubProperties.Reports config;
    private final MarkdownReportRenderer renderer;
    private final JsonMapper jsonMapper;

    public ReportWriter(AgentHubProperties properties, MarkdownReportRenderer renderer, JsonMapper jsonMapper) {
        this.config = properties.reports();
        this.renderer = renderer;
        this.jsonMapper = jsonMapper;
    }

    /** @return caminho do Markdown gerado, se a gravação estiver habilitada e tiver sucesso */
    public Optional<String> write(AnalysisReport report) {
        if (!config.enabled()) {
            return Optional.empty();
        }
        try {
            Path dir = Path.of(config.outputDir()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            String base = FILE_DATE.format(report.generatedAt()) + "-" + report.analysisId().substring(0, 8);
            Path markdown = dir.resolve(base + ".md");
            Files.writeString(markdown, renderer.render(report), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve(base + ".json"),
                    jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
            log.info("event=report.written path={}", markdown);
            return Optional.of(markdown.toString());
        } catch (IOException | RuntimeException e) {
            log.warn("event=report.write_failed analysisId={} error={}", report.analysisId(), e.getMessage());
            return Optional.empty();
        }
    }
}
