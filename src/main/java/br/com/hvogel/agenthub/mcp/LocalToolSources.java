package br.com.hvogel.agenthub.mcp;

import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.ToolCallback;

/**
 * Fontes de ferramentas {@code type: local}, por id de fonte (ex.: {@code filesystem}).
 */
public record LocalToolSources(Map<String, List<ToolCallback>> sources) {

    public LocalToolSources {
        sources = Map.copyOf(sources);
    }

    public List<ToolCallback> get(String sourceId) {
        return sources.getOrDefault(sourceId, List.of());
    }
}
