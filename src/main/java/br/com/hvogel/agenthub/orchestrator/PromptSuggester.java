package br.com.hvogel.agenthub.orchestrator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import br.com.hvogel.agenthub.agents.Agent;
import br.com.hvogel.agenthub.agents.AgentPromptLoader;
import br.com.hvogel.agenthub.agents.AgentRegistry;
import br.com.hvogel.agenthub.claude.CallLimits;
import br.com.hvogel.agenthub.claude.ClaudeResponse;
import br.com.hvogel.agenthub.claude.ClaudeService;
import br.com.hvogel.agenthub.context.ContextSelector;
import br.com.hvogel.agenthub.context.ProjectContext;
import br.com.hvogel.agenthub.context.ProjectDiscoveryService;
import br.com.hvogel.agenthub.model.AgentRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sugere solicitações (prompts) candidatas para a tela "Nova análise", a partir dos agentes escolhidos pelo
 * usuário e do overview do discovery. Uma única chamada ao Claude, sem ferramentas.
 */
@Component
public class PromptSuggester {

    private static final Logger log = LoggerFactory.getLogger(PromptSuggester.class);

    static final int MAX_PROMPT_LENGTH = 4000;
    private static final int MAX_SUGGESTIONS = 6;
    private static final int MAX_OUTPUT_TOKENS = 3000;

    public record PromptSuggestions(List<SuggestionDraft> suggestions) {
    }

    public record SuggestionDraft(String title, String prompt, List<String> agents, String rationale) {
    }

    public record PromptSuggestion(String title, String prompt, List<AgentRole> agents, String rationale) {
    }

    private final AgentRegistry agents;
    private final ClaudeService claude;
    private final ContextSelector contextSelector;
    private final ProjectDiscoveryService discovery;
    private final AgentPromptLoader promptLoader;

    public PromptSuggester(AgentRegistry agents, ClaudeService claude, ContextSelector contextSelector,
                           ProjectDiscoveryService discovery, AgentPromptLoader promptLoader) {
        this.agents = agents;
        this.claude = claude;
        this.contextSelector = contextSelector;
        this.discovery = discovery;
        this.promptLoader = promptLoader;
    }

    public List<PromptSuggestion> suggest(List<AgentRole> requestedRoles, String draft) {
        List<AgentRole> available = agents.enabledRoles();
        List<AgentRole> selected = requestedRoles == null ? List.of()
                : requestedRoles.stream().filter(available::contains).distinct().toList();
        ProjectContext context = discovery.discover();

        String user = """
                # Agentes escolhidos pelo usuário
                %s

                # Agentes disponíveis (use exatamente estes identificadores)
                %s

                # Rascunho atual do usuário
                %s

                # Projeto (resultado do discovery)
                %s
                """.formatted(
                selected.isEmpty() ? "Nenhum: o usuário quer sugestões variadas; escolha os agentes de cada sugestão."
                        : selected.stream().map(AgentRole::name).collect(Collectors.joining(", ")),
                catalog(available),
                draft == null || draft.isBlank() ? "(vazio)" : draft.strip(),
                contextSelector.overview(context));

        ClaudeResponse<PromptSuggestions> response = claude.structured(
                promptLoader.read("classpath:prompts/hub/prompt-suggester.md"), user, List.of(), PromptSuggestions.class,
                new CallLimits(MAX_OUTPUT_TOKENS, null));
        List<PromptSuggestion> suggestions = toSuggestions(response.entity(), selected, available);
        log.info("event=prompt_suggester.done agents={} suggestions={}", selected, suggestions.size());
        return suggestions;
    }

    static List<PromptSuggestion> toSuggestions(PromptSuggestions draft, List<AgentRole> selected, List<AgentRole> available) {
        if (draft == null || draft.suggestions() == null) {
            return List.of();
        }
        List<PromptSuggestion> result = new ArrayList<>();
        for (SuggestionDraft s : draft.suggestions()) {
            if (s == null || s.prompt() == null || s.prompt().isBlank() || result.size() >= MAX_SUGGESTIONS) {
                continue;
            }
            String prompt = s.prompt().strip();
            if (prompt.length() > MAX_PROMPT_LENGTH) {
                prompt = prompt.substring(0, MAX_PROMPT_LENGTH);
            }
            List<AgentRole> roles = selected.isEmpty()
                    ? (s.agents() == null ? List.<AgentRole>of() : s.agents().stream()
                            .filter(Objects::nonNull)
                            .map(AgentRole::parse)
                            .flatMap(Optional::stream)
                            .filter(available::contains)
                            .distinct()
                            .toList())
                    : selected;
            String title = s.title() != null && !s.title().isBlank() ? s.title().strip() : "Sugestão " + (result.size() + 1);
            result.add(new PromptSuggestion(title, prompt, roles, s.rationale()));
        }
        return result;
    }

    private String catalog(List<AgentRole> available) {
        return available.stream()
                .map(role -> agents.find(role).map(Agent::description).map(d -> "- " + role.name() + ": " + d).orElse(""))
                .collect(Collectors.joining("\n"));
    }
}
