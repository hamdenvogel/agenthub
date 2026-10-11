package br.com.hvogel.agenthub.target;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import br.com.hvogel.agenthub.analysis.AnalysisRepository;
import br.com.hvogel.agenthub.analysis.AnalysisRun;
import br.com.hvogel.agenthub.config.AgentHubProperties;
import br.com.hvogel.agenthub.context.ProjectWorkspace;
import br.com.hvogel.agenthub.exception.InvalidTargetException;
import br.com.hvogel.agenthub.exception.TargetBusyException;
import br.com.hvogel.agenthub.support.Fixtures;
import br.com.hvogel.agenthub.target.TargetService.TargetInput;
import br.com.hvogel.agenthub.target.TargetService.TargetStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TargetServiceTest {

    @TempDir
    Path tmp;

    private Path base;
    private Path clientes;
    private Path other;
    private Path storeFile;
    private ProjectWorkspace workspace;
    private AnalysisRepository analyses;

    @BeforeEach
    void setUp() throws IOException {
        base = Files.createDirectories(tmp.resolve("base")).toRealPath();
        clientes = Files.createDirectories(base.resolve("clientes/api/src/main/java/br/app/cliente"));
        Files.writeString(base.resolve("clientes/api/pom.xml"), "<project/>");
        clientes = base.resolve("clientes");
        other = Files.createDirectories(base.resolve("outro"));
        storeFile = tmp.resolve("data/targets.json");
        AgentHubProperties props = Fixtures.properties(clientes.toString(), Fixtures.orchestrator(1, null, 1));
        workspace = new ProjectWorkspace(props);
        analyses = new AnalysisRepository();
    }

    private TargetService service() {
        AgentHubProperties props = Fixtures.properties(clientes.toString(), Fixtures.orchestrator(1, null, 1));
        return new TargetService(new TargetProperties(storeFile.toString(), List.of(base.toString())), props, workspace, analyses);
    }

    @Test
    void firstStartSeedsDefaultProjectAndPersistsIt() {
        TargetService service = service();

        assertThat(service.overview().targets()).singleElement().satisfies(t -> {
            assertThat(t.root()).isEqualTo(clientes.toString());
            assertThat(t.active()).isTrue();
            assertThat(t.available()).isTrue();
        });
        assertThat(storeFile).exists();
        assertThat(workspace.root()).isEqualTo(clientes);
    }

    @Test
    void createWithScopeActivatesAndSurvivesRestart() {
        TargetService service = service();
        String absoluteInclude = clientes.resolve("api/src/main/java/br/app/cliente").toString();

        TargetStatus created = service.create(new TargetInput("Clientes API", clientes.toString(),
                List.of(absoluteInclude, "api\\src\\main\\java\\br\\app\\cliente\\", "api/src/main/java")), true);

        assertThat(created.active()).isTrue();
        assertThat(created.includes()).containsExactly("api/src/main/java");
        assertThat(workspace.includes()).containsExactly("api/src/main/java");

        ProjectWorkspace restarted = new ProjectWorkspace(Fixtures.properties(other.toString(), Fixtures.orchestrator(1, null, 1)));
        TargetService reloaded = new TargetService(new TargetProperties(storeFile.toString(), List.of(base.toString())),
                Fixtures.properties(other.toString(), Fixtures.orchestrator(1, null, 1)), restarted, analyses);
        assertThat(reloaded.overview().activeId()).isEqualTo(created.id());
        assertThat(restarted.root()).isEqualTo(clientes);
        assertThat(restarted.includes()).containsExactly("api/src/main/java");
    }

    @Test
    void rejectsDirectoriesOutsideAllowedRootsAndInvalidScope() throws IOException {
        TargetService service = service();
        Path outside = Files.createDirectories(tmp.resolve("fora"));

        assertThatThrownBy(() -> service.create(new TargetInput("x", outside.toString(), List.of()), false))
                .isInstanceOf(InvalidTargetException.class).hasMessageContaining("fora das pastas permitidas");
        assertThatThrownBy(() -> service.create(new TargetInput("x", base.resolve("nao-existe").toString(), List.of()), false))
                .isInstanceOf(InvalidTargetException.class).hasMessageContaining("não encontrado");
        assertThatThrownBy(() -> service.create(new TargetInput("x", clientes.toString(), List.of("../outro")), false))
                .isInstanceOf(InvalidTargetException.class).hasMessageContaining("fora do diretório");
        assertThatThrownBy(() -> service.create(new TargetInput("x", clientes.toString(), List.of("api/nada")), false))
                .isInstanceOf(InvalidTargetException.class).hasMessageContaining("não encontrado");
    }

    @Test
    void activeProjectCannotChangeWhileAnalysisIsRunning() {
        TargetService service = service();
        TargetStatus outro = service.create(new TargetInput("Outro", other.toString(), List.of()), false);
        analyses.save(new AnalysisRun("a1", "Analise", List.of()));

        assertThatThrownBy(() -> service.activate(outro.id())).isInstanceOf(TargetBusyException.class);
        assertThat(workspace.root()).isEqualTo(clientes);
    }

    @Test
    void activeProjectCannotBeDeleted() {
        TargetService service = service();
        String activeId = service.overview().activeId();
        TargetStatus outro = service.create(new TargetInput("Outro", other.toString(), List.of()), false);

        assertThatThrownBy(() -> service.delete(activeId)).isInstanceOf(InvalidTargetException.class);
        service.delete(outro.id());
        assertThat(service.overview().targets()).hasSize(1);
    }

    @Test
    void databaseUriIsStoredButOnlyReturnedMasked() throws IOException {
        TargetService service = service();
        String uri = "postgresql://leitor:segredo@localhost:5435/meusservicos";

        TargetStatus created = service.create(new TargetInput("Clientes", clientes.toString(), List.of(), uri), true);

        assertThat(created.databaseUri()).isEqualTo("postgresql://leitor:***@localhost:5435/meusservicos");
        assertThat(service.overview().toString()).doesNotContain("segredo");
        assertThat(workspace.database()).contains(uri);
        assertThat(workspace.toString()).doesNotContain("segredo");
        assertThat(Files.readString(storeFile)).contains(uri);

        ProjectWorkspace restarted = new ProjectWorkspace(Fixtures.properties(other.toString(), Fixtures.orchestrator(1, null, 1)));
        new TargetService(new TargetProperties(storeFile.toString(), List.of(base.toString())),
                Fixtures.properties(other.toString(), Fixtures.orchestrator(1, null, 1)), restarted, analyses);
        assertThat(restarted.database()).contains(uri);
    }

    @Test
    void updateKeepsMaskedPasswordAndBlankRemovesDatabase() {
        TargetService service = service();
        String uri = "postgresql://leitor:segredo@localhost:5435/meusservicos";
        TargetStatus created = service.create(new TargetInput("Clientes", clientes.toString(), List.of(), uri), true);

        service.update(created.id(), new TargetInput("Clientes 2", clientes.toString(), List.of(), created.databaseUri()));
        assertThat(workspace.database()).contains(uri);
        service.update(created.id(), new TargetInput("Clientes 3", clientes.toString(), List.of()));
        assertThat(workspace.database()).contains(uri);

        assertThatThrownBy(() -> service.update(created.id(), new TargetInput("x", clientes.toString(), List.of(),
                "postgresql://leitor:***@outro:5435/meusservicos"))).isInstanceOf(InvalidTargetException.class);
        assertThatThrownBy(() -> service.update(created.id(), new TargetInput("x", clientes.toString(), List.of(),
                "jdbc:postgresql://localhost/db"))).isInstanceOf(InvalidTargetException.class);
        assertThat(workspace.database()).contains(uri);

        TargetStatus cleared = service.update(created.id(), new TargetInput("Clientes", clientes.toString(), List.of(), ""));
        assertThat(cleared.databaseUri()).isNull();
        assertThat(workspace.database()).isEmpty();
    }

    @Test
    void browseStaysInsideAllowedRootsAndMarksProjects() {
        TargetService service = service();

        assertThat(service.browse(null).entries()).singleElement().satisfies(e -> assertThat(e.path()).isEqualTo(base.toString()));
        var api = service.browse(clientes.toString()).entries().stream().filter(e -> e.name().equals("api")).findFirst().orElseThrow();
        assertThat(api.markers()).contains("maven", "java");
        assertThat(service.browse(base.toString()).parent()).isNull();
        assertThatThrownBy(() -> service.browse(tmp.toString())).isInstanceOf(InvalidTargetException.class);
    }
}
