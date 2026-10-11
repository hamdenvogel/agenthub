package br.com.hvogel.agenthub.api;

import java.util.List;

import br.com.hvogel.agenthub.api.dto.PromptSuggestionRequest;
import br.com.hvogel.agenthub.orchestrator.PromptSuggester;
import br.com.hvogel.agenthub.orchestrator.PromptSuggester.PromptSuggestion;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/prompt-suggestions")
public class PromptSuggestionController {

    private final PromptSuggester suggester;

    public PromptSuggestionController(PromptSuggester suggester) {
        this.suggester = suggester;
    }

    @PostMapping
    public List<PromptSuggestion> suggest(@Valid @RequestBody PromptSuggestionRequest body) {
        return suggester.suggest(body.agents(), body.draft());
    }
}
