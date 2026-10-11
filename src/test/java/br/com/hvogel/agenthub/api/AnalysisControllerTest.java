package br.com.hvogel.agenthub.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import br.com.hvogel.agenthub.analysis.AnalysisRun;
import br.com.hvogel.agenthub.analysis.AnalysisService;
import br.com.hvogel.agenthub.analysis.MarkdownReportRenderer;
import br.com.hvogel.agenthub.exception.AnalysisNotFoundException;
import br.com.hvogel.agenthub.exception.ReportNotReadyException;
import br.com.hvogel.agenthub.model.AgentRole;
import br.com.hvogel.agenthub.model.AnalysisReport;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AnalysisController.class)
@Import({MarkdownReportRenderer.class, ApiExceptionHandler.class})
class AnalysisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnalysisService service;

    @Test
    void startReturns202WithLocation() throws Exception {
        when(service.submit(eq("Analise a segurança"), any())).thenReturn(new AnalysisRun("abc", "Analise a segurança", List.of(AgentRole.SECURITY)));

        mockMvc.perform(post("/api/analysis").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"request\":\"Analise a segurança\",\"agents\":[\"SECURITY\"]}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/analysis/abc"))
                .andExpect(jsonPath("$.analysisId").value("abc"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void blankRequestIsRejected() throws Exception {
        mockMvc.perform(post("/api/analysis").contentType(MediaType.APPLICATION_JSON).content("{\"request\":\" \"}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).submit(anyString(), any());
    }

    @Test
    void unknownAnalysisReturns404() throws Exception {
        when(service.get("x")).thenThrow(new AnalysisNotFoundException("x"));
        mockMvc.perform(get("/api/analysis/x")).andExpect(status().isNotFound());
    }

    @Test
    void reportNotReadyReturns409() throws Exception {
        when(service.report("abc")).thenThrow(new ReportNotReadyException("abc", "RUNNING"));
        mockMvc.perform(get("/api/analysis/abc/report")).andExpect(status().isConflict());
    }

    @Test
    void reportIsAvailableAsJsonAndMarkdown() throws Exception {
        AnalysisReport report = new AnalysisReport("abc", "Analise", "clientes", Instant.now(), "Resumo executivo",
                null, null, null, null, null, null, null, List.of(), List.of("Rec 1"), List.of(), null, null);
        when(service.report("abc")).thenReturn(report);

        mockMvc.perform(get("/api/analysis/abc/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executiveSummary").value("Resumo executivo"));
        mockMvc.perform(get("/api/analysis/abc/report.md"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("## 1. Executive Summary")))
                .andExpect(content().string(Matchers.containsString("## 11. Suggested Roadmap")));
    }

    @Test
    void cancelReturnsCurrentStatus() throws Exception {
        when(service.cancel("abc")).thenReturn(new AnalysisRun("abc", "Analise", null));
        mockMvc.perform(delete("/api/analysis/abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisId").value("abc"));
    }
}
