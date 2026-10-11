package br.com.hvogel.agenthub.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import br.com.hvogel.agenthub.agents.AgentPromptLoader;
import br.com.hvogel.agenthub.claude.ClaudeResponse;
import br.com.hvogel.agenthub.claude.ClaudeService;
import br.com.hvogel.agenthub.context.ContextSelector;
import br.com.hvogel.agenthub.model.AgentRole;
import br.com.hvogel.agenthub.model.AnalysisReport.RoadmapItem;
import br.com.hvogel.agenthub.model.Severity;
import br.com.hvogel.agenthub.orchestrator.ReportSynthesizer.Synthesis;
import br.com.hvogel.agenthub.orchestrator.ReportSynthesizer.SynthesisDraft;
import br.com.hvogel.agenthub.orchestrator.ResultAggregator.Aggregation;
import br.com.hvogel.agenthub.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class ReportSynthesizerTest {

    private final ClaudeService claude = mock(ClaudeService.class);
    private final ReportSynthesizer synthesizer = new ReportSynthesizer(claude, new ContextSelector(),
            new AgentPromptLoader(new DefaultResourceLoader(), Fixtures.properties()));

    private Aggregation aggregation() {
        return new ResultAggregator().aggregate(Map.of(AgentRole.SECURITY, Fixtures.success(AgentRole.SECURITY, 0.9,
                Fixtures.finding("CORS aberto", Severity.HIGH, "WebSecurityConfig.java"))));
    }

    @Test
    void llmSynthesisKeepsOnlyExistingFindingIds() {
        SynthesisDraft draft = new SynthesisDraft("Resumo executivo", "Qualidade ok",
                List.of(new RoadmapItem("Fase 1 - Imediato", Severity.HIGH, "Restringir CORS", "risco",
                        List.of("SEC-001", "XYZ-999"))),
                List.of("Restringir CORS"));
        when(claude.structured(anyString(), anyString(), anyList(), eq(SynthesisDraft.class)))
                .thenReturn(new ClaudeResponse<>(draft, "claude", 10, 5, 1));

        Synthesis synthesis = synthesizer.synthesize("Analise", Fixtures.emptyProject(), aggregation(), List.of());

        assertThat(synthesis.source()).isEqualTo("llm");
        assertThat(synthesis.roadmap()).singleElement()
                .satisfies(i -> assertThat(i.relatedFindings()).containsExactly("SEC-001"));
    }

    @Test
    void fallsBackToRulesWhenLlmFails() {
        when(claude.structured(anyString(), anyString(), anyList(), eq(SynthesisDraft.class)))
                .thenThrow(new IllegalStateException("timeout"));

        Synthesis synthesis = synthesizer.synthesize("Analise", Fixtures.emptyProject(), aggregation(), List.of("x falhou"));

        assertThat(synthesis.source()).isEqualTo("rules");
        assertThat(synthesis.executiveSummary()).contains("SEC-001").contains("x falhou");
        assertThat(synthesis.roadmap()).singleElement()
                .satisfies(i -> assertThat(i.phase()).isEqualTo("Fase 1 - Imediato"));
    }
}
