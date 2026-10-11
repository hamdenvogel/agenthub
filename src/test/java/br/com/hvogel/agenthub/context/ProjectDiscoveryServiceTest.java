package br.com.hvogel.agenthub.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import br.com.hvogel.agenthub.context.ProjectContext.BackendModule;
import br.com.hvogel.agenthub.context.ProjectContext.FrontendModule;
import br.com.hvogel.agenthub.model.AgentRole;
import br.com.hvogel.agenthub.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectDiscoveryServiceTest {

    @TempDir
    Path root;

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private ProjectDiscoveryService service() {
        return new ProjectDiscoveryService(new ProjectWorkspace(Fixtures.properties(root.toString(), Fixtures.orchestrator(1, null, 1))));
    }

    @Test
    void discoversBackendFrontendAndInfrastructure() throws IOException {
        write("api/pom.xml", """
                <project>
                  <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>3.2.5</version></parent>
                  <artifactId>clientes-api</artifactId>
                  <properties><java.version>21</java.version><jjwt.version>0.12.6</jjwt.version></properties>
                  <dependencies>
                    <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-api</artifactId><version>${jjwt.version}</version></dependency>
                    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
                    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
                  </dependencies>
                </project>
                """);
        write("api/src/main/java/demo/rest/ClienteController.java", "@RestController class ClienteController {}");
        write("api/src/main/java/demo/service/ClienteService.java", "@Service class ClienteService {}");
        write("api/src/main/java/demo/model/Cliente.java", "@Entity class Cliente {}");
        write("api/src/main/java/demo/repo/ClienteRepository.java", "interface ClienteRepository extends JpaRepository<Cliente, Long> {}");
        write("api/src/main/java/demo/security/JwtFilter.java", "class JwtFilter extends OncePerRequestFilter {}");
        write("api/src/test/java/demo/ClienteServiceTest.java", "class ClienteServiceTest {}");
        write("api/src/main/resources/application.properties", "spring.datasource.url=jdbc:postgresql://h/db\nspring.datasource.password=x\n");
        write("api/DDL.sql", "create table cliente();");
        write("api/Procfile", "web: java -jar app.jar");
        write("app/package.json", """
                {"dependencies":{"@angular/core":"^9.1.13","rxjs":"~6.5.4"},"devDependencies":{"typescript":"~3.8.3"}}
                """);
        write("app/package-lock.json", "{}");
        write("app/src/app/app.component.ts", "export class AppComponent {}");
        write("app/src/app/auth.guard.ts", "export class AuthGuard {}");
        write("app/src/app/app-routing.module.ts", "export class AppRoutingModule {}");
        Files.createDirectories(root.resolve("api/.git"));

        ProjectContext ctx = service().discover();

        BackendModule api = ctx.backendModules().getFirst();
        assertThat(api.path()).isEqualTo("api");
        assertThat(api.springBootVersion()).isEqualTo("3.2.5");
        assertThat(api.javaVersion()).isEqualTo("21");
        assertThat(api.dependencies()).contains("io.jsonwebtoken:jjwt-api:0.12.6", "org.postgresql:postgresql (runtime)");
        assertThat(api.artifacts("controllers")).containsExactly("api/src/main/java/demo/rest/ClienteController.java");
        assertThat(api.artifacts("repositories")).hasSize(1);
        assertThat(api.artifacts("entities")).hasSize(1);
        assertThat(api.artifacts("security")).contains("api/src/main/java/demo/security/JwtFilter.java");
        assertThat(api.artifacts("tests")).hasSize(1);

        FrontendModule app = ctx.frontendModules().getFirst();
        assertThat(app.angularVersion()).isEqualTo("^9.1.13");
        assertThat(app.typescriptVersion()).isEqualTo("~3.8.3");
        assertThat(app.packageManager()).isEqualTo("npm");
        assertThat(app.usesSignals()).isFalse();
        assertThat(app.artifacts("guards")).hasSize(1);
        assertThat(app.artifacts("routing")).hasSize(1);

        assertThat(ctx.infrastructure().databases()).anyMatch(d -> d.startsWith("jdbc:postgresql"));
        assertThat(ctx.infrastructure().databases()).noneMatch(d -> d.contains("password"));
        assertThat(ctx.infrastructure().sqlScripts()).containsExactly("api/DDL.sql");
        assertThat(ctx.infrastructure().cloud()).anyMatch(c -> c.startsWith("Heroku"));
        assertThat(ctx.infrastructure().observability()).anyMatch(o -> o.contains("actuator"));
        assertThat(ctx.gitRepositories()).containsExactly("api");

        ContextSelector selector = new ContextSelector();
        assertThat(selector.briefFor(AgentRole.FRONTEND, ctx)).contains("Angular: ^9.1.13").doesNotContain("ClienteController");
        assertThat(selector.briefFor(AgentRole.SECURITY, ctx)).contains("JwtFilter.java").contains("repo_path");
        assertThat(selector.overview(ctx)).contains("Spring Boot 3.2.5");
    }

    @Test
    void failsClearlyWhenProjectIsMissing() {
        ProjectDiscoveryService service = new ProjectDiscoveryService(new ProjectWorkspace(
                Fixtures.properties(root.resolve("nao-existe").toString(), Fixtures.orchestrator(1, null, 1))));
        assertThatThrownBy(service::discover).isInstanceOf(IllegalStateException.class).hasMessageContaining("AGENTHUB_TARGET_ROOT");
    }
}
