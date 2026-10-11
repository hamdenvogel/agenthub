package br.com.hvogel.agenthub.context;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Resultado do Project Discovery: o que existe no projeto analisado, sem conteúdo de arquivos.
 *
 * @param root             caminho absoluto da raiz
 * @param name             nome do projeto
 * @param scope            subdiretórios/arquivos analisados (vazio = projeto inteiro)
 * @param backendModules   módulos Java (Maven/Gradle)
 * @param frontendModules  módulos Angular/Node
 * @param infrastructure   Docker, CI/CD, banco, observabilidade
 * @param gitRepositories  repositórios git (relativos à raiz, "." = raiz, ou absolutos quando acima da raiz)
 * @param totalFiles       total de arquivos indexados
 * @param files            índice completo de arquivos (não serializado na API)
 */
public record ProjectContext(
        String root,
        String name,
        List<String> scope,
        List<BackendModule> backendModules,
        List<FrontendModule> frontendModules,
        Infrastructure infrastructure,
        List<String> gitRepositories,
        int totalFiles,
        @JsonIgnore List<String> files) {

    public ProjectContext {
        scope = scope != null ? List.copyOf(scope) : List.of();
        backendModules = backendModules != null ? List.copyOf(backendModules) : List.of();
        frontendModules = frontendModules != null ? List.copyOf(frontendModules) : List.of();
        gitRepositories = gitRepositories != null ? List.copyOf(gitRepositories) : List.of();
        files = files != null ? List.copyOf(files) : List.of();
    }

    /**
     * @param path              caminho relativo do módulo
     * @param buildTool         maven ou gradle
     * @param artifactId        artifactId/nome do módulo
     * @param javaVersion       versão Java declarada
     * @param springBootVersion versão do Spring Boot declarada
     * @param dependencies      dependências declaradas ({@code groupId:artifactId[:versão]})
     * @param artifacts         arquivos por categoria (controllers, services, repositories, entities,
     *                          dtos, configurations, security, tests, resources)
     */
    public record BackendModule(
            String path,
            String buildTool,
            String artifactId,
            String javaVersion,
            String springBootVersion,
            List<String> dependencies,
            Map<String, List<String>> artifacts) {

        public BackendModule {
            dependencies = dependencies != null ? List.copyOf(dependencies) : List.of();
            artifacts = artifacts != null ? Map.copyOf(artifacts) : Map.of();
        }

        public List<String> artifacts(String category) {
            return artifacts.getOrDefault(category, List.of());
        }
    }

    /**
     * @param path              caminho relativo do módulo
     * @param angularVersion    versão de {@code @angular/core}
     * @param typescriptVersion versão do TypeScript
     * @param rxjsVersion       versão do RxJS
     * @param packageManager    npm, yarn ou pnpm
     * @param usesSignals       uso de Angular Signals detectado
     * @param stateManagement   biblioteca de estado detectada (ou {@code none})
     * @param dependencies      dependências declaradas ({@code nome@versão})
     * @param artifacts         arquivos por categoria (components, services, guards, interceptors,
     *                          routing, modules, tests)
     */
    public record FrontendModule(
            String path,
            String angularVersion,
            String typescriptVersion,
            String rxjsVersion,
            String packageManager,
            boolean usesSignals,
            String stateManagement,
            List<String> dependencies,
            Map<String, List<String>> artifacts) {

        public FrontendModule {
            dependencies = dependencies != null ? List.copyOf(dependencies) : List.of();
            artifacts = artifacts != null ? Map.copyOf(artifacts) : Map.of();
        }

        public List<String> artifacts(String category) {
            return artifacts.getOrDefault(category, List.of());
        }
    }

    /**
     * @param docker         Dockerfile / docker-compose
     * @param ciCd           pipelines (GitHub Actions, GitLab CI, Jenkins, Procfile...)
     * @param kubernetes     manifests k8s/helm
     * @param cloud          indícios de nuvem (AWS, GCP, Heroku...)
     * @param databases      bancos detectados (dependências e URLs JDBC, sem credenciais)
     * @param migrations     migrations (Flyway/Liquibase)
     * @param sqlScripts     scripts SQL avulsos
     * @param configFiles    arquivos de configuração da aplicação
     * @param observability  actuator, micrometer, logging...
     */
    public record Infrastructure(
            List<String> docker,
            List<String> ciCd,
            List<String> kubernetes,
            List<String> cloud,
            List<String> databases,
            List<String> migrations,
            List<String> sqlScripts,
            List<String> configFiles,
            List<String> observability) {

        public Infrastructure {
            docker = copy(docker);
            ciCd = copy(ciCd);
            kubernetes = copy(kubernetes);
            cloud = copy(cloud);
            databases = copy(databases);
            migrations = copy(migrations);
            sqlScripts = copy(sqlScripts);
            configFiles = copy(configFiles);
            observability = copy(observability);
        }

        private static List<String> copy(List<String> values) {
            return values != null ? List.copyOf(values) : List.of();
        }
    }
}
