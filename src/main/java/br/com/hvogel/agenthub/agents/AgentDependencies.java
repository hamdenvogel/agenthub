package br.com.hvogel.agenthub.agents;

import br.com.hvogel.agenthub.claude.ClaudeService;
import br.com.hvogel.agenthub.config.AgentHubProperties;
import br.com.hvogel.agenthub.mcp.McpToolRegistry;
import org.springframework.stereotype.Component;

/**
 * Colaboradores comuns a todos os agentes, agrupados para manter os construtores curtos.
 */
@Component
public record AgentDependencies(
        ClaudeService claude, McpToolRegistry toolRegistry, AgentPromptLoader promptLoader, AgentHubProperties properties) {
}
