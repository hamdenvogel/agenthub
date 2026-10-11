package br.com.hvogel.agenthub.mcp;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import br.com.hvogel.agenthub.config.AgentHubProperties;
import br.com.hvogel.agenthub.context.ProjectWorkspace;
import br.com.hvogel.agenthub.tools.ProjectFilesystemTools;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registro das fontes locais de ferramentas. As conexões MCP (clientes) são criadas
 * pelo auto-configure do Spring AI a partir de {@code spring.ai.mcp.client.*};
 * aqui não se cria cliente MCP manualmente.
 */
@Configuration
public class McpConfiguration {

    /** Busca por conteúdo, que o servidor MCP oficial de filesystem não oferece (o search_files dele busca por nome). */
    static final String CODE_SEARCH_TOOL = "fs_search_text";

    @Bean
    public ProjectFilesystemTools projectFilesystemTools(ProjectWorkspace workspace) {
        return new ProjectFilesystemTools(workspace);
    }

    /**
     * {@code filesystem-local}: as quatro ferramentas in-process (reserva, caso o servidor MCP de filesystem não
     * esteja disponível); {@code code-search}: só a busca por conteúdo, usada junto com o servidor MCP.
     */
    @Bean
    public LocalToolSources localToolSources(ProjectFilesystemTools filesystemTools) {
        List<ToolCallback> all = Arrays.asList(ToolCallbacks.from(filesystemTools));
        List<ToolCallback> search = all.stream()
                .filter(t -> CODE_SEARCH_TOOL.equals(t.getToolDefinition().name()))
                .toList();
        return new LocalToolSources(Map.of("filesystem-local", all, "code-search", search));
    }

    /**
     * Conexões de fontes com {@code bind-to-workspace} declaram a capacidade MCP Roots; a raiz do projeto ativo
     * é informada e mantida pelo {@link McpConnectionTools}.
     *
     * <p>O SDK espera só 20s pelo {@code initialize}; {@code uvx}/{@code npx} no Windows (resolução de pacotes,
     * antivírus, vários servidores subindo juntos) passam disso com frequência.
     */
    @Bean
    public McpClientCustomizer<McpClient.SyncSpec> workspaceRootsCustomizer(AgentHubProperties properties,
            @Value("${agenthub.mcp.initialization-timeout:PT90S}") Duration initializationTimeout) {
        List<String> bound = boundConnections(properties);
        return (name, spec) -> {
            spec.initializationTimeout(initializationTimeout);
            if (bound.contains(name)) {
                spec.capabilities(ClientCapabilities.builder().roots(true).build());
            }
        };
    }

    static List<String> boundConnections(AgentHubProperties properties) {
        return properties.toolSources().entrySet().stream()
                .filter(e -> e.getValue().enabled() && !e.getValue().isLocal() && e.getValue().bindToWorkspace())
                .map(e -> e.getValue().connectionOr(e.getKey()))
                .toList();
    }
}
