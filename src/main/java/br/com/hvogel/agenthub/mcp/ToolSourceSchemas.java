package br.com.hvogel.agenthub.mcp;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonRawValue;

/**
 * Tool Input Schemas de uma fonte de ferramentas, exposto em {@code GET /api/schemas}.
 *
 * @param id           id da fonte
 * @param type         {@code local} ou {@code mcp}
 * @param connection   conexão MCP (type=mcp)
 * @param enabled      habilitada na configuração
 * @param available    pronta para uso (servidor MCP respondeu ao {@code tools/list})
 * @param detail       descrição ou motivo da indisponibilidade
 * @param agents       agentes que recebem esta fonte
 * @param tools        ferramentas expostas aos agentes, com o schema de entrada
 * @param blockedTools ferramentas publicadas pela fonte e não expostas (fora da allowlist ou de escrita)
 */
public record ToolSourceSchemas(
        String id, String type, String connection, boolean enabled, boolean available, String detail,
        List<String> agents, List<ToolSchema> tools, List<String> blockedTools) {

    public ToolSourceSchemas {
        agents = agents != null ? List.copyOf(agents) : List.of();
        tools = tools != null ? List.copyOf(tools) : List.of();
        blockedTools = blockedTools != null ? List.copyOf(blockedTools) : List.of();
    }

    /**
     * @param inputSchema JSON Schema dos argumentos, como enviado ao modelo e validado pelo {@code GuardedToolCallback}
     */
    public record ToolSchema(String name, String description, @JsonRawValue String inputSchema) {
    }
}
