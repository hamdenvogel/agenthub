package br.com.hvogel.agenthub.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;

import br.com.hvogel.agenthub.agents.AgentPromptLoader;
import br.com.hvogel.agenthub.agents.AgentRegistry;
import br.com.hvogel.agenthub.claude.ClaudeResponse;
import br.com.hvogel.agenthub.claude.ClaudeService;
import br.com.hvogel.agenthub.config.AgentHubProperties;
import br.com.hvogel.agenthub.context.ContextSelector;
import br.com.hvogel.agenthub.context.ProjectDiscoveryService;
import br.com.hvogel.agenthub.model.AgentRole;
import br.com.hvogel.agenthub.orchestrator.PromptSuggester.PromptSuggestion;
import br.com.hvogel.agenthub.orchestrator.PromptSuggester.PromptSuggestions;
import br.com.hvogel.agenthub.orchestrator.PromptSuggester.SuggestionDraft;
import br.com.hvogel.agenthub.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class PromptSuggesterTest {

    private final ClaudeService claude = mock(ClaudeService.class);
    private final ProjectDiscoveryService discovery = mock(ProjectDiscoveryService.class);

    private PromptSuggester suggester() {
        AgentHubProperties props = Fixtures.properties();
        AgentRegistry registry = new AgentRegistry(Arrays.stream(AgentRole.values())
                .map(r -> Fixtures.agent(r, (t, c) -> null)).toList(), props);
        when(discovery.discover()).thenReturn(Fixtures.emptyProject());
        return new PromptSuggester(registry, claude, new ContextSelector(), discovery,
                new AgentPromptLoader(new DefaultResourceLoader(), props));
    }

    private void answer(SuggestionDraft... drafts) {
        when(claude.structured(anyString(), anyString(), anyList(), eq(PromptSuggestions.class), any()))
                .thenReturn(new ClaudeResponse<>(new PromptSuggestions(List.of(drafts)), "claude", 10, 10, 1));
    }

    @Test
    void selectedAgentsAreKeptInEverySuggestion() {
        answer(new SuggestionDraft("JWT", "Analise o JWT do backend", List.of("FRONTEND"), "porque sim"),
                new SuggestionDraft(" ", "Revise o CORS", null, null));

        List<PromptSuggestion> result = suggester().suggest(List.of(AgentRole.SECURITY), "rascunho");

        assertThat(result).extracting(PromptSuggestion::agents).containsOnly(List.of(AgentRole.SECURITY));
        assertThat(result).extracting(PromptSuggestion::title).containsExactly("JWT", "Sugestão 2");
    }

    @Test
    void withoutSelectionAgentsComeFromTheModelAndInvalidOnesAreDropped() {
        answer(new SuggestionDraft("Banco", "Revise as entidades", List.of("database", "INEXISTENTE", "DATABASE"), null),
                new SuggestionDraft("Vazia", "  ", List.of("TEST"), null),
                new SuggestionDraft("Longa", "x".repeat(5000), List.of("TEST"), null));

        List<PromptSuggestion> result = suggester().suggest(null, null);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).agents()).containsExactly(AgentRole.DATABASE);
        assertThat(result.get(1).prompt()).hasSize(PromptSuggester.MAX_PROMPT_LENGTH);
    }

    @Test
    void draftAndSelectedAgentsAreSentToTheModel() {
        answer(new SuggestionDraft("A", "Analise", null, null));

        suggester().suggest(List.of(AgentRole.TEST, AgentRole.BACKEND), "quero testes melhores");

        verify(claude).structured(anyString(),
                argThat(user -> user.contains("TEST, BACKEND") && user.contains("quero testes melhores")),
                anyList(), eq(PromptSuggestions.class), any());
    }
}
