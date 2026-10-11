package br.com.hvogel.agenthub.api.dto;

import java.util.List;

import br.com.hvogel.agenthub.model.AgentRole;
import jakarta.validation.constraints.Size;

/**
 * @param agents agentes escolhidos pelo usuário; vazio deixa a IA variar o escopo
 * @param draft  opcional: rascunho já digitado, para a IA refinar
 */
public record PromptSuggestionRequest(
        List<AgentRole> agents,
        @Size(max = 4000) String draft) {
}
