package br.com.hvogel.agenthub.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import br.com.hvogel.agenthub.context.ProjectWorkspace;
import br.com.hvogel.agenthub.context.WorkspaceTarget;
import br.com.hvogel.agenthub.exception.ToolAccessDeniedException;
import br.com.hvogel.agenthub.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectFilesystemToolsTest {

    @TempDir
    Path root;

    private ProjectFilesystemTools tools;

    @BeforeEach
    void setUp() throws IOException {
        write("api/src/main/java/App.java", "public class App {\n  // TODO revisar\n}\n");
        write("api/src/main/resources/application.properties", "spring.datasource.password=segredo123\nserver.port=8080\n");
        write("api/.env", "TOKEN=abc");
        write("api/target/Gerado.java", "class Gerado {}");
        write("app/src/app/app.component.ts", "export class AppComponent {}\n");
        write("big.txt", IntStream.rangeClosed(1, 500).mapToObj(i -> "linha " + i).collect(Collectors.joining("\n")));
        tools = new ProjectFilesystemTools(new ProjectWorkspace(Fixtures.properties(root.toString(), Fixtures.orchestrator(1, null, 1))));
    }

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void readsFileWithLineNumbersAndMaskedSecrets() {
        String content = tools.readFile("api/src/main/resources/application.properties", null, null);
        assertThat(content).contains("1: spring.datasource.password=***MASKED").contains("2: server.port=8080")
                .doesNotContain("segredo123");
    }

    @Test
    void paginatesLargeFiles() {
        String first = tools.readFile("big.txt", null, null);
        assertThat(first).contains("400: linha 400").doesNotContain("401: linha 401").contains("startLine=401");
        assertThat(tools.readFile("big.txt", 495, 10)).contains("500: linha 500");
    }

    @Test
    void deniesPathTraversalSensitiveFilesAndExcludedDirs() {
        assertThatThrownBy(() -> tools.readFile("../fora.txt", null, null)).isInstanceOf(ToolAccessDeniedException.class);
        assertThatThrownBy(() -> tools.readFile("api/.env", null, null)).isInstanceOf(ToolAccessDeniedException.class)
                .hasMessageContaining("sensível");
        assertThatThrownBy(() -> tools.readFile("api/target/Gerado.java", null, null)).isInstanceOf(ToolAccessDeniedException.class);
    }

    @Test
    void findsFilesByGlobIgnoringExcludedDirs() {
        assertThat(tools.findFiles("**/*.java")).contains("api/src/main/java/App.java").doesNotContain("target");
        assertThat(tools.findFiles("**/*.component.ts")).contains("app/src/app/app.component.ts");
    }

    @Test
    void searchesTextSkippingSensitiveFiles() {
        assertThat(tools.searchText("TODO", "**/*.java", null)).contains("api/src/main/java/App.java:2:");
        assertThat(tools.searchText("TOKEN", null, null)).isEqualTo("Nenhuma ocorrência.");
    }

    @Test
    void listsDirectory() {
        assertThat(tools.listDirectory("api")).contains("[dir]  api/src").doesNotContain("target");
    }

    @Test
    void scopeRestrictsReadsSearchesAndListingsButKeepsBuildDescriptors() throws IOException {
        write("api/pom.xml", "<project/>");
        ProjectWorkspace workspace = new ProjectWorkspace(Fixtures.properties(root.toString(), Fixtures.orchestrator(1, null, 1)));
        workspace.switchTo(new WorkspaceTarget("t", "api", root, List.of("api/src/main/java")));
        ProjectFilesystemTools scoped = new ProjectFilesystemTools(workspace);

        assertThat(scoped.readFile("api/src/main/java/App.java", null, null)).contains("public class App");
        assertThat(scoped.readFile("api/pom.xml", null, null)).contains("<project/>");
        assertThatThrownBy(() -> scoped.readFile("app/src/app/app.component.ts", null, null))
                .isInstanceOf(ToolAccessDeniedException.class).hasMessageContaining("escopo");
        assertThatThrownBy(() -> scoped.readFile("api/src/main/resources/application.properties", null, null))
                .isInstanceOf(ToolAccessDeniedException.class);

        assertThat(scoped.findFiles("**/*")).contains("api/src/main/java/App.java", "api/pom.xml")
                .doesNotContain("app.component.ts", "big.txt", "application.properties");
        assertThat(scoped.searchText("class", null, null)).contains("App.java").doesNotContain("AppComponent");
        assertThat(scoped.listDirectory(".")).contains("[dir]  api").doesNotContain("[dir]  app").doesNotContain("big.txt");
        assertThat(scoped.listDirectory("api/src")).contains("api/src/main").doesNotContain("api/src/test");
    }
}
