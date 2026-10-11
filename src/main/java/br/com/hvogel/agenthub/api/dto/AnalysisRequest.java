package br.com.hvogel.agenthub.api.dto;

import java.util.List;

import br.com.hvogel.agenthub.model.AgentRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param request solicitação em linguagem natural
 * @param agents  opcional: força quais especialistas acionar (ignora o Planner)
 */
public record AnalysisRequest(
        @NotBlank @Size(max = 4000) String request,
        List<AgentRole> agents) {
}
