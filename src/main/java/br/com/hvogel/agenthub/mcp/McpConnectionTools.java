package br.com.hvogel.agenthub.mcp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import br.com.hvogel.agenthub.config.AgentHubProperties;
import br.com.hvogel.agenthub.context.ProjectWorkspace;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Root;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Acesso às ferramentas de cada conexão MCP gerenciada pelo Spring AI.
 *
 * <p>Os clientes são criados pelo auto-configure com {@code spring.ai.mcp.client.initialized=false}
 * e inicializados aqui sob demanda: se um servidor MCP não subir (ex.: {@code uvx} ausente),
 * a aplicação continua de pé e só aquela fonte fica indisponível.
 *
 * <p>Conexões presas ao workspace (ex.: servidor oficial de filesystem) recebem a raiz do projeto ativo via
 * MCP Roots antes da inicialização e a cada troca de projeto; o servidor só acessa essa pasta.
 */
@Component
public class McpConnectionTools {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionTools.class);

    private static final String LIST_ROOTS_TOOL = "list_allowed_directories";
    private static final Duration ROOT_CONFIRM_TIMEOUT = Duration.ofSeconds(3);

    private final ObjectProvider<List<McpSyncClient>> clientsProvider;
    private final ProjectWorkspace workspace;
    private final List<String> boundConnections;
    private final Map<String, List<ToolCallback>> cache = new ConcurrentHashMap<>();
    private final Map<String, String> failures = new ConcurrentHashMap<>();
    private final Map<String, String> roots = new ConcurrentHashMap<>();

    private final ProjectDatabaseConnection projectDatabase;

    public McpConnectionTools(ObjectProvider<List<McpSyncClient>> clientsProvider, AgentHubProperties properties,
                              ProjectWorkspace workspace, ProjectDatabaseConnection projectDatabase) {
        this.clientsProvider = clientsProvider;
        this.workspace = workspace;
        this.boundConnections = McpConfiguration.boundConnections(properties);
        this.projectDatabase = projectDatabase;
    }

    /**
     * Ferramentas expostas pela conexão MCP.
     *
     * @throws IllegalStateException se a conexão não existir ou não inicializar
     */
    public List<ToolCallback> tools(String connection) {
        if (projectDatabase.handles(connection)) {
            return projectDatabase.tools();
        }
        List<ToolCallback> cached = cache.get(connection);
        if (cached != null) {
            syncRoot(connection);
            return cached;
        }
        synchronized (this) {
            cached = cache.get(connection);
            if (cached != null) {
                return cached;
            }
            McpSyncClient client = find(connection).orElseThrow(() -> new IllegalStateException(
                    "Conexão MCP '" + connection + "' não configurada em spring.ai.mcp.client.stdio.connections"
                            + " (ou spring.ai.mcp.client.enabled=false)."));
            String previousFailure = failures.get(connection);
            if (previousFailure != null && !client.isInitialized()) {
                throw new IllegalStateException("Servidor MCP '" + connection + "' indisponível: " + previousFailure
                        + " (reinicie o backend para tentar novamente).");
            }
            try {
                long start = System.nanoTime();
                syncRoot(connection, client);
                if (!client.isInitialized()) {
                    client.initialize();
                    if (boundConnections.contains(connection)) {
                        awaitRoot(connection, client);
                    }
                }
                List<ToolCallback> tools = List.of(SyncMcpToolCallbackProvider.builder()
                        .mcpClients(client)
                        .toolNamePrefixGenerator(McpToolNamePrefixGenerator.noPrefix())
                        .build()
                        .getToolCallbacks());
                cache.put(connection, tools);
                failures.remove(connection);
                log.info("event=mcp.connected connection={} server={} tools={} durationMs={}",
                        connection, client.getServerInfo() != null ? client.getServerInfo().name() : "?",
                        tools.size(), (System.nanoTime() - start) / 1_000_000);
                return tools;
            } catch (RuntimeException e) {
                String reason = rootCause(e);
                failures.put(connection, reason);
                log.warn("event=mcp.unavailable connection={} error={}", connection, reason, e);
                throw new IllegalStateException("Servidor MCP '" + connection + "' indisponível: " + reason, e);
            }
        }
    }

    public boolean isConfigured(String connection) {
        return projectDatabase.handles(connection) || find(connection).isPresent();
    }

    public Optional<String> failure(String connection) {
        return Optional.ofNullable(failures.get(connection));
    }

    /**
     * No Windows, encerrar o processo do launcher ({@code uvx}, {@code npx.cmd}) não encerra o servidor filho;
     * sem isso, cada reinício do backend deixa processos MCP órfãos.
     */
    @PreDestroy
    void stopServerProcesses() {
        ProcessHandle.current().descendants().forEach(ProcessHandle::destroy);
    }

    private void syncRoot(String connection) {
        if (boundConnections.contains(connection)) {
            find(connection).ifPresent(client -> {
                if (syncRoot(connection, client) && client.isInitialized()) {
                    awaitRoot(connection, client);
                }
            });
        }
    }

    /**
     * Troca a raiz informada ao servidor quando o projeto ativo muda (adiciona a nova antes de remover a antiga).
     *
     * @return se a raiz mudou
     */
    private synchronized boolean syncRoot(String connection, McpSyncClient client) {
        if (!boundConnections.contains(connection)) {
            return false;
        }
        Path root = workspace.root().toAbsolutePath().normalize();
        String uri = root.toUri().toString();
        String previous = roots.get(connection);
        if (uri.equals(previous)) {
            return false;
        }
        client.addRoot(new Root(uri, workspace.name()));
        roots.put(connection, uri);
        if (previous != null) {
            try {
                client.removeRoot(previous);
            } catch (RuntimeException e) {
                log.warn("event=mcp.root_remove_failed connection={} root={} error={}", connection, previous, e.getMessage());
            }
        }
        log.info("event=mcp.root connection={} root={}", connection, root);
        return true;
    }

    /**
     * O servidor busca as raízes de forma assíncrona após {@code initialize}/{@code roots/list_changed}; sem
     * esperar, a primeira chamada de ferramenta pode ser negada por "fora dos diretórios permitidos".
     */
    private void awaitRoot(String connection, McpSyncClient client) {
        String expected = workspace.root().toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        long deadline = System.nanoTime() + ROOT_CONFIRM_TIMEOUT.toNanos();
        try {
            while (System.nanoTime() < deadline) {
                CallToolResult result = client.callTool(new CallToolRequest(LIST_ROOTS_TOOL, Map.of()));
                String text = result.content().stream()
                        .filter(TextContent.class::isInstance)
                        .map(c -> ((TextContent) c).text())
                        .collect(Collectors.joining("\n"))
                        .toLowerCase(Locale.ROOT);
                if (text.lines().anyMatch(line -> line.strip().equals(expected))) {
                    return;
                }
                Thread.sleep(100);
            }
            log.warn("event=mcp.root_unconfirmed connection={} timeoutMs={}", connection, ROOT_CONFIRM_TIMEOUT.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("event=mcp.root_unconfirmed connection={} error={}", connection, e.getMessage());
        }
    }

    /**
     * O SDK embrulha a falha real (processo encerrado, timeout, URI inválida) em "Client failed to initialize".
     * O transporte stdio não pode ser reconectado: o cliente que falhou fica inutilizável até o restart.
     */
    private static String rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause == e ? String.valueOf(e.getMessage())
                : e.getMessage() + " — causa: " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    /** O auto-configure grava o nome da conexão no {@code title} do clientInfo. */
    private Optional<McpSyncClient> find(String connection) {
        List<McpSyncClient> clients = clientsProvider.getIfAvailable(List::of);
        return clients.stream()
                .filter(c -> c.getClientInfo() != null && connection.equals(c.getClientInfo().title()))
                .findFirst();
    }
}
