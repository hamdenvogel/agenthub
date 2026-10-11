package br.com.hvogel.agenthub.api;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import br.com.hvogel.agenthub.agents.AgentRegistry;
import br.com.hvogel.agenthub.api.dto.AgentInfo;
import br.com.hvogel.agenthub.context.ProjectContext;
import br.com.hvogel.agenthub.context.ProjectDiscoveryService;
import br.com.hvogel.agenthub.mcp.McpToolRegistry;
import br.com.hvogel.agenthub.mcp.ToolSourceStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inspeção sem custo de LLM: agentes, fontes de ferramentas (MCP/local) e discovery.
 */
@RestController
@RequestMapping("/api")
public class AgentController {

    private final AgentRegistry agents;
    private final McpToolRegistry toolRegistry;
    private final ProjectDiscoveryService discovery;

    public AgentController(AgentRegistry agents, McpToolRegistry toolRegistry, ProjectDiscoveryService discovery) {
        this.agents = agents;
        this.toolRegistry = toolRegistry;
        this.discovery = discovery;
    }

    @GetMapping("/agents")
    public Map<String, Object> agents() {
        List<AgentInfo> list = agents.enabled().stream()
                .sorted(Comparator.comparing(a -> a.role().ordinal()))
                .map(a -> new AgentInfo(a.role().id(), a.name(), a.description(), a.toolSources()))
                .toList();
        List<ToolSourceStatus> sources = toolRegistry.statuses();
        return Map.of("agents", list, "toolSources", sources);
    }

    @GetMapping("/project")
    public ProjectContext project() {
        return discovery.discover();
    }
}
