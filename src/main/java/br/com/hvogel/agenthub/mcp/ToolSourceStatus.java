package br.com.hvogel.agenthub.mcp;

import java.util.List;

/**
 * Estado de uma fonte de ferramentas, exposto em {@code GET /api/agents}.
 *
 * @param id          id da fonte
 * @param type        {@code local} ou {@code mcp}
 * @param connection  conexão MCP (type=mcp)
 * @param enabled     habilitada na configuração
 * @param available   pronta para uso
 * @param tools       ferramentas expostas após allowlist e bloqueio de escrita
 * @param detail      descrição ou motivo da indisponibilidade
 */
public record ToolSourceStatus(
        String id, String type, String connection, boolean enabled, boolean available, List<String> tools, String detail) {

    public ToolSourceStatus {
        tools = tools != null ? List.copyOf(tools) : List.of();
    }
}
