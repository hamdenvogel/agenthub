package br.com.hvogel.agenthub.mcp;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import br.com.hvogel.agenthub.config.AgentHubProperties;
import br.com.hvogel.agenthub.config.AgentHubProperties.ToolSourceProperties;
import br.com.hvogel.agenthub.context.ProjectWorkspace;
import br.com.hvogel.agenthub.exception.ToolAccessDeniedException;
import br.com.hvogel.agenthub.mcp.ToolSourceSchemas.ToolSchema;
import br.com.hvogel.agenthub.model.AgentRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Resolve as ferramentas de cada agente aplicando least privilege em três níveis:
 * <ol>
 *   <li>o agente só recebe as fontes listadas em {@code agenthub.agents.<id>.tool-sources};</li>
 *   <li>de cada fonte, só as ferramentas da {@code allowed-tools} (quando informada);</li>
 *   <li>ferramentas com nome de operação de escrita são bloqueadas, salvo {@code allow-write: true}.</li>
 * </ol>
 */
@Component
public class McpToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);

    static final Pattern WRITE_OPERATION = Pattern.compile(
            "(?i).*(^|_|-)(write|edit|delete|remove|create|move|rename|commit|push|reset|checkout|add|init|update|insert|drop|alter|truncate|exec|execute|run|merge|rebase|stash|tag|apply)($|_|-).*");

    static final Set<String> LISTING_TOOLS = Set.of("list_directory", "search_files");

    private static final Pattern LISTING_ENTRY = Pattern.compile("^\\[(?:FILE|DIR)\\]\\s+(.+?)\\s*$");

    private final AgentHubProperties properties;
    private final LocalToolSources localSources;
    private final McpConnectionTools mcpConnections;
    private final ProjectWorkspace workspace;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public McpToolRegistry(AgentHubProperties properties, LocalToolSources localSources, McpConnectionTools mcpConnections,
                           ProjectWorkspace workspace) {
        this.properties = properties;
        this.localSources = localSources;
        this.mcpConnections = mcpConnections;
        this.workspace = workspace;
    }

    /**
     * Ferramentas permitidas ao agente, já decoradas com orçamento e observabilidade.
     * Fontes indisponíveis são ignoradas (com log); o agente segue com as demais.
     */
    public List<ToolCallback> toolsFor(AgentRole role, ToolBudget budget) {
        return toolsFor(role.id(), properties.agent(role.id()).toolSources(), budget);
    }

    /**
     * Ferramentas das fontes informadas para quem não é um agente de análise (ex.: correção assistida),
     * com as mesmas regras de allowlist, bloqueio de escrita e argumentos-caminho.
     */
    public List<ToolCallback> toolsFor(String agent, List<String> sourceIds, ToolBudget budget) {
        List<ToolCallback> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (String sourceId : sourceIds) {
            ToolSourceProperties source = properties.toolSources().get(sourceId);
            UnaryOperator<String> inputGuard = pathGuard(source);
            for (ToolCallback tool : allowedTools(sourceId)) {
                String name = tool.getToolDefinition().name();
                if (names.add(name)) {
                    result.add(new GuardedToolCallback(tool, sourceId, agent, budget, inputGuard, listingGuard(source, name)));
                } else {
                    log.warn("event=tool.duplicate agent={} source={} tool={}", agent, sourceId, name);
                }
            }
        }
        return result;
    }

    /**
     * Argumentos-caminho da fonte precisam apontar para o projeto ativo. Em fontes presas ao workspace,
     * cada caminho (texto ou lista) passa pelas regras de {@link ProjectWorkspace#resolve(String)} e segue
     * como absoluto; nas demais (ex.: {@code repo_path} do git) só é verificado.
     */
    UnaryOperator<String> pathGuard(ToolSourceProperties source) {
        if (source == null || source.pathArguments().isEmpty()) {
            return UnaryOperator.identity();
        }
        return input -> {
            JsonNode parsed;
            try {
                parsed = jsonMapper.readTree(input == null || input.isBlank() ? "{}" : input);
            } catch (RuntimeException e) {
                throw new ToolAccessDeniedException("Argumentos inválidos (JSON esperado).");
            }
            if (!(parsed instanceof ObjectNode args)) {
                throw new ToolAccessDeniedException("Argumentos inválidos (objeto JSON esperado).");
            }
            for (String argument : source.pathArguments()) {
                JsonNode value = args.path(argument);
                if (value.isString() && !value.asString().isBlank()) {
                    String checked = checkPath(source, value.asString());
                    args.put(argument, checked);
                } else if (value.isArray()) {
                    ArrayNode checked = jsonMapper.createArrayNode();
                    for (JsonNode item : value) {
                        if (!item.isString() || item.asString().isBlank()) {
                            throw new ToolAccessDeniedException("Caminho inválido em " + argument + ".");
                        }
                        checked.add(checkPath(source, item.asString()));
                    }
                    args.set(argument, checked);
                }
            }
            return source.bindToWorkspace() ? jsonMapper.writeValueAsString(args) : input;
        };
    }

    private String checkPath(ToolSourceProperties source, String path) {
        if (source.bindToWorkspace()) {
            return workspace.resolve(path).toString();
        }
        workspace.requireWithinProject(path);
        return path;
    }

    /**
     * Listagens de fontes presas ao workspace seguem a visibilidade do índice local: entradas de diretórios
     * excluídos (.git, node_modules, target...) ou fora do escopo não chegam ao modelo. O retorno MCP é a
     * lista de conteúdo em JSON; só os itens de texto são filtrados.
     */
    UnaryOperator<String> listingGuard(ToolSourceProperties source, String tool) {
        if (source == null || !source.bindToWorkspace() || !LISTING_TOOLS.contains(tool)) {
            return UnaryOperator.identity();
        }
        return result -> {
            JsonNode parsed;
            try {
                parsed = jsonMapper.readTree(result == null || result.isBlank() ? "[]" : result);
            } catch (RuntimeException e) {
                return filterListing(result);
            }
            if (!parsed.isArray()) {
                return result;
            }
            for (JsonNode item : parsed) {
                if (item instanceof ObjectNode content && content.path("text").isString()) {
                    content.put("text", filterListing(content.path("text").asString()));
                }
            }
            return jsonMapper.writeValueAsString(parsed);
        };
    }

    private String filterListing(String text) {
        if (text == null) {
            return null;
        }
        List<String> visible = text.lines().filter(this::visibleEntry).toList();
        return visible.isEmpty() ? "(nenhuma entrada visível)" : String.join("\n", visible);
    }

    private boolean visibleEntry(String line) {
        try {
            Matcher entry = LISTING_ENTRY.matcher(line);
            if (entry.matches()) {
                return !workspace.isExcludedDir(Path.of(entry.group(1)));
            }
            Path path = Path.of(line.trim());
            if (!path.isAbsolute()) {
                return true;
            }
            Path normalized = path.normalize();
            if (!normalized.startsWith(workspace.root())) {
                return false;
            }
            for (Path part : workspace.root().relativize(normalized)) {
                if (workspace.isExcludedDir(part)) {
                    return false;
                }
            }
            return workspace.isVisible(normalized);
        } catch (InvalidPathException e) {
            return true;
        }
    }

    /** Fontes declaradas para o agente (independente de disponibilidade). */
    public List<String> sourcesFor(AgentRole role) {
        return properties.agent(role.id()).toolSources();
    }

    public List<ToolSourceStatus> statuses() {
        List<ToolSourceStatus> statuses = new ArrayList<>();
        new TreeMap<>(properties.toolSources()).forEach((id, source) -> statuses.add(status(id, source)));
        return statuses;
    }

    private ToolSourceStatus status(String id, ToolSourceProperties source) {
        if (!source.enabled()) {
            return new ToolSourceStatus(id, source.type(), source.connection(), false, false, List.of(),
                    "Desabilitada. " + source.description());
        }
        try {
            List<String> tools = filtered(id, source).stream().map(t -> t.getToolDefinition().name()).sorted().toList();
            return new ToolSourceStatus(id, source.type(), source.connection(), true, true, tools, source.description());
        } catch (RuntimeException e) {
            return new ToolSourceStatus(id, source.type(), source.connection(), true, false, List.of(), e.getMessage());
        }
    }

    /** Tool Input Schemas de todas as fontes configuradas, com quem as usa e o que foi bloqueado. */
    public List<ToolSourceSchemas> schemas() {
        List<ToolSourceSchemas> result = new ArrayList<>();
        new TreeMap<>(properties.toolSources()).forEach((id, source) -> {
            List<String> agents = Arrays.stream(AgentRole.values())
                    .filter(role -> sourcesFor(role).contains(id))
                    .map(AgentRole::id)
                    .toList();
            if (!source.enabled()) {
                result.add(new ToolSourceSchemas(id, source.type(), source.connection(), false, false,
                        "Desabilitada. " + source.description(), agents, List.of(), List.of()));
                return;
            }
            try {
                List<ToolSchema> tools = new ArrayList<>();
                List<String> blocked = new ArrayList<>();
                for (ToolCallback tool : raw(id, source)) {
                    ToolDefinition definition = tool.getToolDefinition();
                    if (isAllowed(id, source, definition.name())) {
                        tools.add(new ToolSchema(definition.name(), definition.description(), definition.inputSchema()));
                    } else {
                        blocked.add(definition.name());
                    }
                }
                tools.sort(Comparator.comparing(ToolSchema::name));
                blocked.sort(Comparator.naturalOrder());
                result.add(new ToolSourceSchemas(id, source.type(), source.connection(), true, true,
                        source.description(), agents, tools, blocked));
            } catch (RuntimeException e) {
                result.add(new ToolSourceSchemas(id, source.type(), source.connection(), true, false,
                        e.getMessage(), agents, List.of(), List.of()));
            }
        });
        return result;
    }

    /** Ferramentas permitidas da fonte; fonte desconhecida, desabilitada ou fora do ar resulta em lista vazia. */
    private List<ToolCallback> allowedTools(String sourceId) {
        ToolSourceProperties source = properties.toolSources().get(sourceId);
        if (source == null) {
            log.warn("event=tool.source.unknown source={}", sourceId);
            return List.of();
        }
        if (!source.enabled()) {
            return List.of();
        }
        try {
            return filtered(sourceId, source);
        } catch (IllegalStateException e) {
            log.warn("event=tool.source.unavailable source={} reason={}", sourceId, e.getMessage());
            return List.of();
        }
    }

    private List<ToolCallback> filtered(String sourceId, ToolSourceProperties source) {
        return raw(sourceId, source).stream().filter(t -> isAllowed(sourceId, source, t.getToolDefinition().name())).toList();
    }

    private List<ToolCallback> raw(String sourceId, ToolSourceProperties source) {
        return source.isLocal() ? localSources.get(sourceId) : mcpConnections.tools(source.connectionOr(sourceId));
    }

    static boolean isAllowed(String sourceId, ToolSourceProperties source, String toolName) {
        if (!source.allowedTools().isEmpty() && !source.allowedTools().contains(toolName)) {
            return false;
        }
        if (!source.allowWrite() && WRITE_OPERATION.matcher(toolName).matches()) {
            log.debug("event=tool.blocked_write source={} tool={}", sourceId, toolName);
            return false;
        }
        return true;
    }

    /** Matriz agente -> fontes, para documentação/inspeção. */
    public Map<String, List<String>> accessMatrix() {
        Map<String, List<String>> matrix = new TreeMap<>();
        for (AgentRole role : AgentRole.values()) {
            matrix.put(role.id(), sourcesFor(role));
        }
        return matrix;
    }
}
