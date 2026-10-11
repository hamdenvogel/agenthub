package br.com.hvogel.agenthub;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.hvogel.agenthub.agents.AgentRegistry;
import br.com.hvogel.agenthub.mcp.McpToolRegistry;
import br.com.hvogel.agenthub.model.AgentRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.ai.anthropic.api-key=test-key",
        "spring.ai.mcp.client.enabled=false",
        "agenthub.mcp.warm-up=false",
        "agenthub.reports.enabled=false",
        "agenthub.targets.store-file=target/test-data/targets.json",
        "agenthub.remediation.store-dir=target/test-data/remediations"
})
class AgentHubApplicationTests {

    @Autowired
    private AgentRegistry agents;

    @Autowired
    private McpToolRegistry toolRegistry;

    @Test
    void contextLoadsWithAllAgentsAndLeastPrivilegeMatrix() {
        assertThat(agents.enabledRoles()).containsExactlyInAnyOrder(AgentRole.values());
        assertThat(toolRegistry.accessMatrix().get("frontend"))
                .containsExactly("filesystem", "code-search", "git", "sonarqube");
        assertThat(toolRegistry.accessMatrix().get("database"))
                .containsExactly("filesystem", "code-search", "git", "database");
        assertThat(toolRegistry.accessMatrix()).allSatisfy((agent, sources) -> {
            assertThat(sources).doesNotContain("filesystem-local");
            if (!agent.equals("database")) {
                assertThat(sources).doesNotContain("database");
            }
        });
        assertThat(toolRegistry.statuses()).filteredOn(s -> s.id().equals("code-search"))
                .singleElement().satisfies(s -> assertThat(s.tools()).containsExactly("fs_search_text"));
        assertThat(toolRegistry.statuses()).filteredOn(s -> s.id().equals("filesystem-local"))
                .singleElement().satisfies(s -> assertThat(s.tools())
                        .containsExactlyInAnyOrder("fs_list_directory", "fs_read_file", "fs_search_text", "fs_find_files"));
        assertThat(toolRegistry.statuses()).filteredOn(s -> s.id().equals("filesystem") || s.id().equals("git"))
                .hasSize(2).allSatisfy(s -> {
                    assertThat(s.type()).isEqualTo("mcp");
                    assertThat(s.available()).isFalse();
                });
    }
}
