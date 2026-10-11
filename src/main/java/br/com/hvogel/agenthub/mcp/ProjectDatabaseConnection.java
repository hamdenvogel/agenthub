package br.com.hvogel.agenthub.mcp;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import br.com.hvogel.agenthub.context.ProjectWorkspace;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Conexão MCP com o banco do projeto ativo. Cada projeto informa a própria URI (tela Projetos,
 * {@code data/targets.json}); ao trocar de projeto ou de URI, o processo do servidor anterior é encerrado e um novo
 * sobe com a URI nova. Projeto sem banco = fonte indisponível (o agente segue só com o código).
 *
 * <p>A troca de projeto só acontece sem análise em andamento, então nenhuma execução usa o cliente que é fechado.
 */
@Component
public class ProjectDatabaseConnection {

    private static final Logger log = LoggerFactory.getLogger(ProjectDatabaseConnection.class);

    private final ProjectDatabaseProperties properties;
    private final ProjectWorkspace workspace;
    private final Function<String, McpSyncClient> clientFactory;

    private String currentUri;
    private McpSyncClient client;
    private List<ToolCallback> tools = List.of();

    @Autowired
    public ProjectDatabaseConnection(ProjectDatabaseProperties properties, ProjectWorkspace workspace) {
        this(properties, workspace, null);
    }

    ProjectDatabaseConnection(ProjectDatabaseProperties properties, ProjectWorkspace workspace,
                              Function<String, McpSyncClient> clientFactory) {
        this.properties = properties;
        this.workspace = workspace;
        this.clientFactory = clientFactory != null ? clientFactory : this::newClient;
    }

    public boolean handles(String connection) {
        return properties.connection().equals(connection);
    }

    /**
     * Ferramentas do servidor ligado ao banco do projeto ativo.
     *
     * @throws IllegalStateException se o projeto ativo não tiver banco ou o servidor não subir
     */
    public synchronized List<ToolCallback> tools() {
        String uri = workspace.database().orElse(null);
        if (uri == null) {
            close();
            throw new IllegalStateException(
                    "Projeto ativo (" + workspace.name() + ") sem banco configurado. Informe a URI na tela Projetos.");
        }
        if (uri.equals(currentUri) && client != null) {
            return tools;
        }
        close();
        long start = System.nanoTime();
        McpSyncClient next = clientFactory.apply(uri);
        try {
            next.initialize();
            tools = List.of(SyncMcpToolCallbackProvider.builder()
                    .mcpClients(next)
                    .toolNamePrefixGenerator(McpToolNamePrefixGenerator.noPrefix())
                    .build()
                    .getToolCallbacks());
        } catch (RuntimeException e) {
            closeQuietly(next);
            log.warn("event=mcp.unavailable connection={} project={} error={}", properties.connection(), workspace.name(),
                    e.getMessage());
            throw new IllegalStateException("Servidor MCP do banco indisponível para o projeto " + workspace.name()
                    + ": " + e.getMessage(), e);
        }
        client = next;
        currentUri = uri;
        log.info("event=mcp.connected connection={} project={} tools={} durationMs={}", properties.connection(),
                workspace.name(), tools.size(), (System.nanoTime() - start) / 1_000_000);
        return tools;
    }

    @PreDestroy
    public synchronized void close() {
        if (client != null) {
            closeQuietly(client);
            log.info("event=mcp.closed connection={}", properties.connection());
        }
        client = null;
        currentUri = null;
        tools = List.of();
    }

    private McpSyncClient newClient(String uri) {
        ServerParameters params = ServerParameters.builder(properties.command())
                .args(properties.args())
                .env(Map.of(properties.uriVariable(), uri))
                .build();
        StdioClientTransport transport = new StdioClientTransport(params, new JacksonMcpJsonMapper(JsonMapper.shared()));
        return McpClient.sync(transport)
                .initializationTimeout(properties.initializationTimeout())
                .requestTimeout(properties.requestTimeout())
                .clientInfo(new Implementation("agenthub-" + properties.connection(), "0.1.0"))
                .build();
    }

    private static void closeQuietly(McpSyncClient c) {
        try {
            c.closeGracefully();
        } catch (RuntimeException e) {
            c.close();
        }
    }
}
