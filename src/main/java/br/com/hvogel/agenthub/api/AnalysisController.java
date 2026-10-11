package br.com.hvogel.agenthub.api;

import java.net.URI;
import java.util.List;

import br.com.hvogel.agenthub.analysis.AnalysisRun;
import br.com.hvogel.agenthub.analysis.AnalysisService;
import br.com.hvogel.agenthub.analysis.MarkdownReportRenderer;
import br.com.hvogel.agenthub.api.dto.AnalysisRequest;
import br.com.hvogel.agenthub.api.dto.AnalysisResponse;
import br.com.hvogel.agenthub.model.AnalysisReport;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private static final String TEXT_MARKDOWN = "text/markdown;charset=UTF-8";

    private final AnalysisService service;
    private final MarkdownReportRenderer renderer;

    public AnalysisController(AnalysisService service, MarkdownReportRenderer renderer) {
        this.service = service;
        this.renderer = renderer;
    }

    /** Inicia a análise de forma assíncrona: 202 + Location para acompanhar. */
    @PostMapping
    public ResponseEntity<AnalysisResponse> start(@Valid @RequestBody AnalysisRequest body) {
        AnalysisRun run = service.submit(body.request(), body.agents());
        return ResponseEntity.accepted()
                .location(URI.create("/api/analysis/" + run.id()))
                .body(AnalysisResponse.from(run));
    }

    @GetMapping
    public List<AnalysisResponse> list() {
        return service.list().stream().map(AnalysisResponse::summary).toList();
    }

    @GetMapping("/{analysisId}")
    public AnalysisResponse status(@PathVariable String analysisId) {
        return AnalysisResponse.from(service.get(analysisId));
    }

    @GetMapping(value = "/{analysisId}/report", produces = MediaType.APPLICATION_JSON_VALUE)
    public AnalysisReport report(@PathVariable String analysisId) {
        return service.report(analysisId);
    }

    @GetMapping(value = "/{analysisId}/report.md", produces = TEXT_MARKDOWN)
    public String reportMarkdown(@PathVariable String analysisId) {
        return renderer.render(service.report(analysisId));
    }

    @DeleteMapping("/{analysisId}")
    public AnalysisResponse cancel(@PathVariable String analysisId) {
        return AnalysisResponse.from(service.cancel(analysisId));
    }
}
