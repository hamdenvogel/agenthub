package br.com.hvogel.agenthub.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import br.com.hvogel.agenthub.config.AgentHubProperties.ToolSourceProperties;
import br.com.hvogel.agenthub.context.ProjectWorkspace;
import br.com.hvogel.agenthub.model.AgentRole;
import br.com.hvogel.agenthub.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class McpToolRegistryTest {

    private final McpConnectionTools mcp = mock(McpConnectionTools.class);
    private final LocalToolSources local = new LocalToolSources(Map.of("filesystem", List.of(tool("fs_read_file"), tool("fs_find_files"))));
    private final McpToolRegistry registry = new McpToolRegistry(Fixtures.properties(), local, mcp,
            new ProjectWorkspace(Fixtures.properties()));

    static ToolCallback tool(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return name + ":" + toolInput;
            }
        };
    }

    private static List<String> names(List<ToolCallback> tools) {
        return tools.stream().map(t -> t.getToolDefinition().name()).toList();
    }

    @Test
    void agentReceivesOnlyItsSources() {
        List<ToolCallback> backend = registry.toolsFor(AgentRole.BACKEND, new ToolBudget(5));
        assertThat(names(backend)).containsExactlyInAnyOrder("fs_read_file", "fs_find_files");
        assertThat(backend).allMatch(t -> t instanceof GuardedToolCallback g && g.source().equals("filesystem"));
        verify(mcp, never()).tools("git");
    }

    @Test
    void mcpSourceAppliesAllowlistAndBlocksWriteOperations() {
        when(mcp.tools("git")).thenReturn(List.of(tool("git_log"), tool("git_status"), tool("git_commit"), tool("git_show")));

        List<ToolCallback> security = registry.toolsFor(AgentRole.SECURITY, new ToolBudget(5));

        assertThat(names(security)).contains("git_log", "git_status").doesNotContain("git_commit", "git_show");
    }

    @Test
    void writeOperationsAreBlockedEvenWithoutAllowlist() {
        ToolSourceProperties open = new ToolSourceProperties(true, "mcp", "x", List.of(), false, List.of(), "", false);
        assertThat(McpToolRegistry.isAllowed("x", open, "git_log")).isTrue();
        assertThat(McpToolRegistry.isAllowed("x", open, "git_commit")).isFalse();
        assertThat(McpToolRegistry.isAllowed("x", open, "write_file")).isFalse();
        assertThat(McpToolRegistry.isAllowed("x", open, "git_create_branch")).isFalse();
        assertThat(McpToolRegistry.isAllowed("x", open, "fs_read_file")).isTrue();

        ToolSourceProperties writable = new ToolSourceProperties(true, "mcp", "x", List.of(), true, List.of(), "", false);
        assertThat(McpToolRegistry.isAllowed("x", writable, "git_commit")).isTrue();
    }

    @Test
    void unavailableMcpServerIsSkippedAndAgentKeepsOtherSources() {
        when(mcp.tools("git")).thenThrow(new IllegalStateException("uvx não encontrado"));

        List<ToolCallback> security = registry.toolsFor(AgentRole.SECURITY, new ToolBudget(5));

        assertThat(names(security)).containsExactlyInAnyOrder("fs_read_file", "fs_find_files");
        assertThat(registry.statuses()).filteredOn(s -> s.id().equals("git"))
                .singleElement().satisfies(s -> {
                    assertThat(s.available()).isFalse();
                    assertThat(s.detail()).contains("uvx");
                });
    }

    @Test
    void disabledSourceIsNeverQueried() {
        List<ToolCallback> database = registry.toolsFor(AgentRole.DATABASE, new ToolBudget(5));
        assertThat(names(database)).containsExactlyInAnyOrder("fs_read_file", "fs_find_files");
        verify(mcp, never()).tools("postgres");
        assertThat(registry.statuses()).filteredOn(s -> s.id().equals("database"))
                .singleElement().satisfies(s -> assertThat(s.enabled()).isFalse());
    }

    @Test
    void repoPathOutsideActiveProjectIsDenied(@TempDir Path root) {
        Path other = root.resolveSibling(root.getFileName() + "-other");
        ProjectWorkspace workspace = new ProjectWorkspace(Fixtures.properties(root.toString(), Fixtures.orchestrator(1, null, 1)));
        McpToolRegistry scoped = new McpToolRegistry(Fixtures.properties(), local, mcp, workspace);
        when(mcp.tools("git")).thenReturn(List.of(tool("git_log")));

        ToolCallback gitLog = scoped.toolsFor(AgentRole.SECURITY, new ToolBudget(5)).stream()
                .filter(t -> t.getToolDefinition().name().equals("git_log")).findFirst().orElseThrow();

        String inside = gitLog.call("{\"repo_path\":\"" + root.resolve("api").toString().replace("\\", "/") + "\"}");
        String outside = gitLog.call("{\"repo_path\":\"" + other.toString().replace("\\", "/") + "\"}");

        assertThat(inside).startsWith("git_log:");
        assertThat(outside).startsWith("ACESSO NEGADO");
    }

    @Test
    void schemasListExposedToolsWithInputSchemaAndBlockedOnes() {
        when(mcp.tools("git")).thenReturn(List.of(tool("git_log"), tool("git_commit"), tool("git_show")));

        List<ToolSourceSchemas> schemas = registry.schemas();

        assertThat(schemas).filteredOn(s -> s.id().equals("git")).singleElement().satisfies(s -> {
            assertThat(s.available()).isTrue();
            assertThat(s.tools()).extracting(ToolSourceSchemas.ToolSchema::name).containsExactly("git_log");
            assertThat(s.tools().getFirst().inputSchema()).isEqualTo("{}");
            assertThat(s.blockedTools()).containsExactly("git_commit", "git_show");
            assertThat(s.agents()).contains("security");
        });
        assertThat(schemas).filteredOn(s -> s.id().equals("filesystem")).singleElement()
                .satisfies(s -> assertThat(s.tools()).extracting(ToolSourceSchemas.ToolSchema::name)
                        .containsExactly("fs_find_files", "fs_read_file"));
        assertThat(schemas).filteredOn(s -> s.id().equals("database")).singleElement()
                .satisfies(s -> assertThat(s.enabled()).isFalse());
        verify(mcp, never()).tools("postgres");
    }

    @Test
    void workspaceBoundSourceResolvesPathsAndDeniesSensitiveFiles(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/App.java"), "class App {}");
        Files.writeString(root.resolve(".env"), "SECRET=1");
        var props = Fixtures.properties(root.toString(), Fixtures.orchestrator(1, null, 1));
        McpToolRegistry scoped = new McpToolRegistry(props, local, mcp, new ProjectWorkspace(props));
        when(mcp.tools("files")).thenReturn(List.of(tool("read_text_file"), tool("read_multiple_files"), tool("write_file")));

        List<ToolCallback> tools = scoped.toolsFor("remediation", List.of("files"), new ToolBudget(10));
        assertThat(names(tools)).containsExactly("read_text_file", "read_multiple_files");
        ToolCallback read = tools.get(0);
        ToolCallback readMany = tools.get(1);
        String app = root.resolve("src/App.java").toAbsolutePath().normalize().toString();

        assertThat(sentArgs(read.call("{\"path\":\"src/App.java\"}")).path("path").asString()).isEqualTo(app);
        assertThat(sentArgs(read.call("{\"path\":\"" + app.replace("\\", "\\\\") + "\"}")).path("path").asString()).isEqualTo(app);
        assertThat(sentArgs(readMany.call("{\"paths\":[\"src/App.java\"]}")).path("paths").get(0).asString()).isEqualTo(app);
        assertThat(read.call("{\"path\":\".env\"}")).startsWith("ACESSO NEGADO").contains("sensível");
        assertThat(read.call("{\"path\":\"../fora.txt\"}")).startsWith("ACESSO NEGADO");
        assertThat(readMany.call("{\"paths\":[\"src/App.java\",\".env\"]}")).startsWith("ACESSO NEGADO");
    }

    @Test
    void workspaceBoundListingsHideExcludedDirectoriesLikeTheLocalIndex(@TempDir Path root) {
        var props = Fixtures.properties(root.toString(), Fixtures.orchestrator(1, null, 1));
        McpToolRegistry scoped = new McpToolRegistry(props, local, mcp, new ProjectWorkspace(props));
        ToolSourceProperties files = props.toolSources().get("files");
        String src = root.resolve("src").resolve("App.java").toString();
        String dep = root.resolve("node_modules").resolve("lib").resolve("App.java").toString();
        String listing = "[{\"type\":\"text\",\"text\":" + JsonMapper.builder().build().writeValueAsString(
                "[FILE] .env\n[DIR] .git\n[DIR] node_modules\n[DIR] src\n" + src + "\n" + dep + "\nC:\\fora\\x.txt") + "}]";

        JsonNode filtered = JsonMapper.builder().build().readTree(scoped.listingGuard(files, "list_directory").apply(listing));

        assertThat(filtered.get(0).path("text").asString().lines().toList())
                .containsExactly("[FILE] .env", "[DIR] src", src);
        assertThat(scoped.listingGuard(files, "read_text_file").apply(listing)).isEqualTo(listing);
        assertThat(scoped.listingGuard(props.toolSources().get("git"), "list_directory").apply(listing)).isEqualTo(listing);
    }

    @Test
    void onlyEnabledMcpSourcesWithBindToWorkspaceDeclareRoots() {
        assertThat(McpConfiguration.boundConnections(Fixtures.properties())).containsExactly("files");
    }

    private static JsonNode sentArgs(String echoed) {
        assertThat(echoed).contains(":{");
        return JsonMapper.builder().build().readTree(echoed.substring(echoed.indexOf(':') + 1));
    }

    @Test
    void accessMatrixReflectsConfiguration() {
        assertThat(registry.accessMatrix())
                .containsEntry("security", List.of("filesystem", "git"))
                .containsEntry("database", List.of("filesystem", "database"));
    }
}
