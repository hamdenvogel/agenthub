package br.com.hvogel.agenthub.context;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import br.com.hvogel.agenthub.context.ProjectContext.BackendModule;
import br.com.hvogel.agenthub.context.ProjectContext.FrontendModule;
import br.com.hvogel.agenthub.context.ProjectContext.Infrastructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Project Discovery determinístico (sem LLM): identifica stack, versões, módulos e
 * artefatos do projeto analisado. Serve de base para o Planner e para o ContextSelector.
 */
@Service
public class ProjectDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(ProjectDiscoveryService.class);

    private static final Pattern JDBC_URL = Pattern.compile("jdbc:([a-z0-9]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANGULAR_SIGNALS = Pattern.compile(
            "import\\s*\\{[^}]*\\b(signal|computed|effect)\\b[^}]*}\\s*from\\s*['\"]@angular/core['\"]");
    private static final Pattern GRADLE_BOOT = Pattern.compile("org\\.springframework\\.boot['\"]?\\)?\\s*version\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern GRADLE_JAVA = Pattern.compile("(?:languageVersion\\s*=\\s*JavaLanguageVersion\\.of\\((\\d+)\\)|sourceCompatibility\\s*=\\s*['\"]?(?:JavaVersion\\.VERSION_)?([\\d_.]+))");

    private static final List<String> DB_MARKERS = List.of("postgresql", "ojdbc", "oracle", "mysql", "mariadb", "h2database", "mssql", "sqlserver", "mongodb", "redis");
    private static final List<String> OBSERVABILITY_MARKERS = List.of("actuator", "micrometer", "prometheus", "opentelemetry", "sentry", "logstash", "zipkin", "brave");

    private final ProjectWorkspace workspace;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public ProjectDiscoveryService(ProjectWorkspace workspace) {
        this.workspace = workspace;
    }

    public ProjectContext discover() {
        if (!workspace.exists()) {
            throw new IllegalStateException("Projeto analisado não encontrado em " + workspace.root()
                    + ". Selecione outro projeto na tela Projetos (ou ajuste AGENTHUB_TARGET_ROOT).");
        }
        long start = System.nanoTime();
        List<String> files = workspace.listFiles(workspace.root());

        List<BackendModule> backend = files.stream()
                .filter(f -> fileName(f).equals("pom.xml") || fileName(f).startsWith("build.gradle"))
                .map(f -> discoverBackendModule(f, files))
                .flatMap(Optional::stream)
                .toList();
        List<FrontendModule> frontend = files.stream()
                .filter(f -> fileName(f).equals("package.json"))
                .map(f -> discoverFrontendModule(f, files))
                .flatMap(Optional::stream)
                .toList();
        Infrastructure infrastructure = discoverInfrastructure(files, backend, frontend);
        List<String> gitRepositories = discoverGitRepositories();

        ProjectContext context = new ProjectContext(
                workspace.root().toString(), workspace.name(), workspace.includes(), backend, frontend, infrastructure,
                gitRepositories, files.size(), files);
        log.info("event=discovery.done project={} scope={} files={} backendModules={} frontendModules={} durationMs={}",
                context.name(), context.scope(), files.size(), backend.size(), frontend.size(),
                (System.nanoTime() - start) / 1_000_000);
        return context;
    }

    // ------------------------------------------------------------------ backend

    private Optional<BackendModule> discoverBackendModule(String buildFile, List<String> files) {
        String modulePath = parent(buildFile);
        try {
            String content = workspace.readText(workspace.root().resolve(buildFile));
            BuildInfo info = fileName(buildFile).equals("pom.xml") ? parsePom(content) : parseGradle(content);
            String prefix = modulePath.isEmpty() ? "" : modulePath + "/";
            Map<String, List<String>> artifacts = classifyJavaSources(prefix, files);
            String buildTool = fileName(buildFile).equals("pom.xml") ? "maven" : "gradle";
            return Optional.of(new BackendModule(
                    modulePath.isEmpty() ? "." : modulePath, buildTool, info.artifactId(),
                    info.javaVersion(), info.springBootVersion(), info.dependencies(), artifacts));
        } catch (Exception e) {
            log.warn("event=discovery.backend.skip file={} reason={}", buildFile, e.getMessage());
            return Optional.empty();
        }
    }

    private Map<String, List<String>> classifyJavaSources(String prefix, List<String> files) {
        Map<String, List<String>> artifacts = new LinkedHashMap<>();
        for (String category : List.of("controllers", "services", "repositories", "entities", "dtos",
                "configurations", "security", "tests", "resources")) {
            artifacts.put(category, new ArrayList<>());
        }
        String mainJava = prefix + "src/main/java/";
        String testJava = prefix + "src/test/java/";
        String resources = prefix + "src/main/resources/";
        for (String file : files) {
            if (file.startsWith(testJava) && file.endsWith(".java")) {
                artifacts.get("tests").add(file);
            } else if (file.startsWith(resources) && isConfigFile(file)) {
                artifacts.get("resources").add(file);
            } else if (file.startsWith(mainJava) && file.endsWith(".java")) {
                classifyJavaFile(file, artifacts);
            }
        }
        artifacts.replaceAll((k, v) -> List.copyOf(v));
        return artifacts;
    }

    private void classifyJavaFile(String file, Map<String, List<String>> artifacts) {
        String content;
        try {
            content = workspace.readText(workspace.root().resolve(file));
        } catch (Exception e) {
            content = "";
        }
        String lowerPath = file.toLowerCase(Locale.ROOT);
        String name = fileName(file);
        if (content.contains("@RestController") || content.contains("@Controller")) {
            artifacts.get("controllers").add(file);
        }
        if (content.contains("@Service")) {
            artifacts.get("services").add(file);
        }
        if (content.contains("@Repository") || content.matches("(?s).*extends\\s+(Jpa|Crud|PagingAndSorting|ListCrud)Repository.*")) {
            artifacts.get("repositories").add(file);
        }
        if (content.contains("@Entity")) {
            artifacts.get("entities").add(file);
        }
        if (lowerPath.contains("/dto/") || name.matches(".*(Dto|DTO|Request|Response)\\.java")) {
            artifacts.get("dtos").add(file);
        }
        if (content.contains("@Configuration") || content.contains("@ConfigurationProperties")) {
            artifacts.get("configurations").add(file);
        }
        if (lowerPath.contains("/security/") || name.matches(".*(Security|Jwt|Auth|Token).*\\.java")
                || content.contains("SecurityFilterChain") || content.contains("OncePerRequestFilter")) {
            artifacts.get("security").add(file);
        }
    }

    private BuildInfo parsePom(String content) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(content)));
        Element project = doc.getDocumentElement();

        Map<String, String> properties = new HashMap<>();
        Element props = child(project, "properties");
        if (props != null) {
            NodeList list = props.getChildNodes();
            for (int i = 0; i < list.getLength(); i++) {
                if (list.item(i) instanceof Element e) {
                    properties.put(e.getTagName(), e.getTextContent().trim());
                }
            }
        }
        String springBoot = null;
        Element parent = child(project, "parent");
        if (parent != null && "spring-boot-starter-parent".equals(text(parent, "artifactId"))) {
            springBoot = text(parent, "version");
        }
        String javaVersion = firstNonNull(properties.get("java.version"), properties.get("maven.compiler.release"),
                properties.get("maven.compiler.source"));

        List<String> dependencies = new ArrayList<>();
        Element deps = child(project, "dependencies");
        if (deps != null) {
            NodeList list = deps.getElementsByTagName("dependency");
            for (int i = 0; i < list.getLength(); i++) {
                Element dep = (Element) list.item(i);
                String version = resolveProperty(text(dep, "version"), properties);
                String coordinate = text(dep, "groupId") + ":" + text(dep, "artifactId")
                        + (version != null ? ":" + version : "");
                String scope = text(dep, "scope");
                dependencies.add(scope != null && !"compile".equals(scope) ? coordinate + " (" + scope + ")" : coordinate);
            }
        }
        if (springBoot == null) {
            springBoot = dependencies.stream()
                    .filter(d -> d.startsWith("org.springframework.boot:spring-boot-dependencies:"))
                    .map(d -> d.split(":")[2])
                    .findFirst().orElse(null);
        }
        return new BuildInfo(text(project, "artifactId"), javaVersion, springBoot, dependencies);
    }

    private BuildInfo parseGradle(String content) {
        Matcher boot = GRADLE_BOOT.matcher(content);
        Matcher java = GRADLE_JAVA.matcher(content);
        String javaVersion = java.find() ? firstNonNull(java.group(1), java.group(2)) : null;
        List<String> dependencies = content.lines()
                .map(String::trim)
                .filter(l -> l.matches("(implementation|api|runtimeOnly|compileOnly|testImplementation)\\b.*"))
                .toList();
        return new BuildInfo(null, javaVersion, boot.find() ? boot.group(1) : null, dependencies);
    }

    // ----------------------------------------------------------------- frontend

    private Optional<FrontendModule> discoverFrontendModule(String packageJson, List<String> files) {
        String modulePath = parent(packageJson);
        try {
            JsonNode root = jsonMapper.readTree(workspace.readText(workspace.root().resolve(packageJson)));
            Map<String, String> deps = new LinkedHashMap<>();
            collectDependencies(root.path("dependencies"), deps, "");
            collectDependencies(root.path("devDependencies"), deps, " (dev)");
            if (deps.keySet().stream().noneMatch(d -> d.startsWith("@angular/") || d.equals("react") || d.equals("vue"))) {
                return Optional.empty();
            }
            String prefix = modulePath.isEmpty() ? "" : modulePath + "/";
            List<String> moduleFiles = files.stream().filter(f -> f.startsWith(prefix + "src/")).toList();
            Map<String, List<String>> artifacts = new LinkedHashMap<>();
            artifacts.put("components", filter(moduleFiles, f -> f.endsWith(".component.ts")));
            artifacts.put("services", filter(moduleFiles, f -> f.endsWith(".service.ts")));
            artifacts.put("guards", filter(moduleFiles, f -> f.endsWith(".guard.ts")));
            artifacts.put("interceptors", filter(moduleFiles, f -> f.endsWith(".interceptor.ts")));
            artifacts.put("routing", filter(moduleFiles, f -> f.endsWith("-routing.module.ts") || f.endsWith(".routes.ts")));
            artifacts.put("modules", filter(moduleFiles, f -> f.endsWith(".module.ts") && !f.endsWith("-routing.module.ts")));
            artifacts.put("tests", filter(moduleFiles, f -> f.endsWith(".spec.ts")));

            boolean signals = moduleFiles.stream()
                    .filter(f -> f.endsWith(".ts"))
                    .anyMatch(f -> readQuietly(f).map(c -> ANGULAR_SIGNALS.matcher(c).find()).orElse(false));
            String state = deps.keySet().stream()
                    .filter(d -> d.startsWith("@ngrx/store") || d.startsWith("@ngxs/") || d.startsWith("@datorama/akita")
                            || d.startsWith("@ngneat/elf") || d.equals("redux"))
                    .findFirst().orElse("none");
            return Optional.of(new FrontendModule(
                    modulePath.isEmpty() ? "." : modulePath,
                    clean(deps.get("@angular/core")), clean(deps.get("typescript")), clean(deps.get("rxjs")),
                    packageManager(prefix, files), signals, state,
                    deps.entrySet().stream().map(e -> e.getKey() + "@" + e.getValue()).toList(),
                    artifacts));
        } catch (Exception e) {
            log.warn("event=discovery.frontend.skip file={} reason={}", packageJson, e.getMessage());
            return Optional.empty();
        }
    }

    private static void collectDependencies(JsonNode node, Map<String, String> deps, String suffix) {
        if (node != null && node.isObject()) {
            node.properties().forEach(e -> deps.put(e.getKey(), e.getValue().asString() + suffix));
        }
    }

    private static String packageManager(String prefix, List<String> files) {
        if (files.contains(prefix + "pnpm-lock.yaml")) {
            return "pnpm";
        }
        if (files.contains(prefix + "yarn.lock")) {
            return "yarn";
        }
        return files.contains(prefix + "package-lock.json") ? "npm" : "npm (sem lockfile)";
    }

    // ----------------------------------------------------------- infrastructure

    private Infrastructure discoverInfrastructure(List<String> files, List<BackendModule> backend, List<FrontendModule> frontend) {
        List<String> allDeps = new ArrayList<>();
        backend.forEach(m -> allDeps.addAll(m.dependencies()));
        frontend.forEach(m -> allDeps.addAll(m.dependencies()));

        List<String> docker = filter(files, f -> fileName(f).toLowerCase(Locale.ROOT).startsWith("dockerfile")
                || fileName(f).matches("(?i)(docker-)?compose.*\\.ya?ml"));
        List<String> ci = filter(files, f -> f.contains(".github/workflows/") || fileName(f).equals(".gitlab-ci.yml")
                || fileName(f).equals("Jenkinsfile") || fileName(f).equals("azure-pipelines.yml")
                || fileName(f).equals("bitbucket-pipelines.yml") || fileName(f).equals("cloudbuild.yaml"));
        List<String> k8s = filter(files, f -> f.matches("(?i).*(^|/)(k8s|kubernetes|helm|charts)/.*\\.ya?ml"));

        Set<String> cloud = new LinkedHashSet<>();
        files.stream().filter(f -> fileName(f).equals("Procfile")).forEach(f -> cloud.add("Heroku (" + f + ")"));
        files.stream().filter(f -> fileName(f).equals("app.yaml") || fileName(f).equals("cloudbuild.yaml"))
                .forEach(f -> cloud.add("Google Cloud (" + f + ")"));
        files.stream().filter(f -> f.contains(".ebextensions/") || fileName(f).equals("serverless.yml"))
                .forEach(f -> cloud.add("AWS (" + f + ")"));
        allDeps.stream().filter(d -> d.contains("aws") || d.contains("amazon")).forEach(d -> cloud.add("AWS SDK (" + d + ")"));
        allDeps.stream().filter(d -> d.contains("spring-cloud-gcp") || d.contains("google-cloud")).forEach(d -> cloud.add("GCP SDK (" + d + ")"));

        List<String> configFiles = filter(files, this::isConfigFile);
        Set<String> databases = new LinkedHashSet<>();
        allDeps.stream()
                .filter(d -> DB_MARKERS.stream().anyMatch(m -> d.toLowerCase(Locale.ROOT).contains(m)))
                .forEach(d -> databases.add("dependência " + d));
        for (String config : configFiles) {
            readQuietly(config).ifPresent(content -> {
                Matcher m = JDBC_URL.matcher(content);
                while (m.find()) {
                    databases.add("jdbc:" + m.group(1).toLowerCase(Locale.ROOT) + " (" + config + ")");
                }
            });
        }
        List<String> migrations = filter(files, f -> f.contains("db/migration/") || f.contains("db/changelog/")
                || f.matches("(?i).*/V\\d+(_\\d+)*__.*\\.sql"));
        List<String> sql = filter(files, f -> f.toLowerCase(Locale.ROOT).endsWith(".sql") && !migrations.contains(f));

        Set<String> observability = new LinkedHashSet<>();
        allDeps.stream()
                .filter(d -> OBSERVABILITY_MARKERS.stream().anyMatch(m -> d.toLowerCase(Locale.ROOT).contains(m)))
                .forEach(observability::add);
        files.stream().filter(f -> fileName(f).matches("(?i)(logback|log4j2?)(-[\\w]+)?\\.(xml|properties|ya?ml)"))
                .forEach(observability::add);

        return new Infrastructure(docker, ci, k8s, List.copyOf(cloud), List.copyOf(databases), migrations, sql,
                configFiles, List.copyOf(observability));
    }

    private List<String> discoverGitRepositories() {
        List<String> repos = new ArrayList<>();
        Path root = workspace.root();
        if (Files.exists(root.resolve(".git"))) {
            repos.add(".");
        }
        try (var children = Files.list(root)) {
            children.filter(Files::isDirectory)
                    .filter(workspace::isVisible)
                    .filter(dir -> Files.exists(dir.resolve(".git")))
                    .forEach(dir -> repos.add(workspace.relativize(dir)));
        } catch (IOException e) {
            log.warn("event=discovery.git.skip reason={}", e.getMessage());
        }
        if (repos.isEmpty()) {
            workspace.enclosingGitRepository().ifPresent(repo -> repos.add(repo.toString().replace('\\', '/')));
        }
        return repos;
    }

    // ------------------------------------------------------------------ helpers

    private boolean isConfigFile(String file) {
        String name = fileName(file);
        return name.matches("(?i)(application|bootstrap)(-[\\w]+)?\\.(properties|ya?ml)")
                || name.matches("(?i)environment(\\.[\\w]+)?\\.ts")
                || name.equals("angular.json") || name.equals("proxy.conf.json");
    }

    private Optional<String> readQuietly(String relative) {
        try {
            return Optional.of(workspace.readText(workspace.root().resolve(relative)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static List<String> filter(List<String> files, Predicate<String> predicate) {
        return files.stream().filter(predicate).toList();
    }

    private static String fileName(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    private static String parent(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(0, idx) : "";
    }

    private static String clean(String version) {
        return version == null ? null : version.replace(" (dev)", "");
    }

    private static Element child(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element e && e.getTagName().equals(tag)) {
                return e;
            }
        }
        return null;
    }

    private static String text(Element parent, String tag) {
        Element e = child(parent, tag);
        return e != null ? e.getTextContent().trim() : null;
    }

    private static String resolveProperty(String value, Map<String, String> properties) {
        if (value != null && value.startsWith("${") && value.endsWith("}")) {
            return properties.getOrDefault(value.substring(2, value.length() - 1), value);
        }
        return value;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private record BuildInfo(String artifactId, String javaVersion, String springBootVersion, List<String> dependencies) {
    }
}
