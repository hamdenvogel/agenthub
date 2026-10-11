package br.com.hvogel.agenthub.api;

import java.util.List;

import br.com.hvogel.agenthub.analysis.MarkdownReportRenderer;
import br.com.hvogel.agenthub.analysis.ReportArchive;
import br.com.hvogel.agenthub.analysis.ReportArchive.ArchivedReport;
import br.com.hvogel.agenthub.model.AnalysisReport;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Relatórios gravados em disco (histórico que sobrevive a reinícios).
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportArchive archive;
    private final MarkdownReportRenderer renderer;

    public ReportController(ReportArchive archive, MarkdownReportRenderer renderer) {
        this.archive = archive;
        this.renderer = renderer;
    }

    @GetMapping
    public List<ArchivedReport> list() {
        return archive.list();
    }

    @GetMapping(value = "/{reportId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public AnalysisReport report(@PathVariable String reportId) {
        return archive.load(reportId);
    }

    @GetMapping(value = "/{reportId}/report.md", produces = "text/markdown;charset=UTF-8")
    public String markdown(@PathVariable String reportId) {
        return renderer.render(archive.load(reportId));
    }
}
