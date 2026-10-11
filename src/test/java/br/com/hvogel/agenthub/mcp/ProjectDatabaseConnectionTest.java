package br.com.hvogel.agenthub.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import br.com.hvogel.agenthub.context.ProjectWorkspace;
import br.com.hvogel.agenthub.context.WorkspaceTarget;
import br.com.hvogel.agenthub.support.Fixtures;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectDatabaseConnectionTest {

    @TempDir
    Path tmp;

    private final ProjectDatabaseProperties properties = new ProjectDatabaseProperties(null, null, null, null, null, null);
    private final List<String> startedWith = new ArrayList<>();
    private final List<McpSyncClient> clients = new ArrayList<>();

    private ProjectDatabaseConnection connection(ProjectWorkspace workspace) {
        return new ProjectDatabaseConnection(properties, workspace, uri -> {
            startedWith.add(uri);
            McpSyncClient client = mock(McpSyncClient.class);
            when(client.listTools()).thenReturn(new ListToolsResult(List.of(), null));
            clients.add(client);
            return client;
        });
    }

    private ProjectWorkspace workspace(String databaseUri) {
        ProjectWorkspace workspace = new ProjectWorkspace(Fixtures.properties(tmp.toString(), Fixtures.orchestrator(1, null, 1)));
        workspace.switchTo(new WorkspaceTarget("p1", "Clientes", tmp, List.of(), databaseUri));
        return workspace;
    }

    @Test
    void projectWithoutDatabaseMakesSourceUnavailable() {
        ProjectDatabaseConnection connection = connection(workspace(null));

        assertThat(connection.handles("postgres")).isTrue();
        assertThatThrownBy(connection::tools).isInstanceOf(IllegalStateException.class).hasMessageContaining("sem banco");
        assertThat(startedWith).isEmpty();
    }

    @Test
    void reusesServerForSameDatabaseAndRestartsWhenProjectChanges() {
        ProjectWorkspace workspace = workspace("postgresql://u:p@localhost/a");
        ProjectDatabaseConnection connection = connection(workspace);

        connection.tools();
        connection.tools();
        assertThat(startedWith).containsExactly("postgresql://u:p@localhost/a");

        workspace.switchTo(new WorkspaceTarget("p2", "Outro", tmp, List.of(), "postgresql://u:p@localhost/b"));
        connection.tools();
        assertThat(startedWith).containsExactly("postgresql://u:p@localhost/a", "postgresql://u:p@localhost/b");
        verify(clients.getFirst()).closeGracefully();

        workspace.switchTo(new WorkspaceTarget("p3", "Sem banco", tmp, List.of()));
        assertThatThrownBy(connection::tools).isInstanceOf(IllegalStateException.class);
        verify(clients.get(1)).closeGracefully();
    }
}
