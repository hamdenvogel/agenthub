package br.com.hvogel.agenthub.target;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import br.com.hvogel.agenthub.analysis.AnalysisRepository;
import br.com.hvogel.agenthub.config.AgentHubProperties;
import br.com.hvogel.agenthub.context.ProjectWorkspace;
import br.com.hvogel.agenthub.context.WorkspaceTarget;
import br.com.hvogel.agenthub.exception.InvalidTargetException;
import br.com.hvogel.agenthub.exception.TargetBusyException;
import br.com.hvogel.agenthub.exception.TargetNotFoundException;
import br.com.hvogel.agenthub.target.TargetStore.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Projetos-alvo cadastrados pela UI: o diretório (e, opcionalmente, o escopo) que os agentes
 * analisam. O alvo ativo é aplicado ao {@link ProjectWorkspace} em tempo de execução, sem restart.
 *
 * <p>Regras: alvos só dentro das pastas-base permitidas ({@code agenthub.targets.allowed-roots});
 * o alvo ativo não muda enquanto houver análise em andamento (as trocas e o início de uma
 * análise sincronizam no próprio {@code ProjectWorkspace}).
 */
@Service
public class TargetService {

    private static final Logger log = LoggerFactory.getLogger(TargetService.class);

    static final int MAX_TARGETS = 50;
    static final int MAX_INCLUDES = 30;
    static final int MAX_NAME = 80;
    static final int MAX_BROWSE_ENTRIES = 500;

    /**
     * @param id        identificador
     * @param name      nome
     * @param root      raiz absoluta
     * @param includes  escopo (vazio = projeto inteiro)
     * @param createdAt criação
     * @param updatedAt última alteração
     * @param active    é o alvo ativo
     * @param available diretório existe e está dentro das pastas permitidas
     * @param problem   motivo quando indisponível
     * @param databaseUri banco do projeto com a senha mascarada ({@code null} = sem banco)
     */
    public record TargetStatus(String id, String name, String root, List<String> includes, Instant createdAt,
                               Instant updatedAt, boolean active, boolean available, String problem,
                               String databaseUri) {
    }

    /**
     * @param activeId        alvo ativo
     * @param targets         alvos cadastrados
     * @param allowedRoots    pastas-base permitidas
     * @param runningAnalyses análises em andamento (bloqueiam a troca do alvo ativo)
     * @param storeFile       arquivo onde os alvos são gravados
     */
    public record TargetOverview(String activeId, List<TargetStatus> targets, List<String> allowedRoots,
                                 long runningAnalyses, String storeFile) {
    }

    /**
     * @param name     nome (default: nome da pasta)
     * @param root     diretório raiz
     * @param includes subdiretórios/arquivos (absolutos dentro da raiz ou relativos a ela)
     * @param databaseUri banco do projeto: {@code null} mantém o atual, vazio remove, a versão mascarada devolvida
     *                    pela API mantém a senha gravada
     */
    public record TargetInput(String name, String root, List<String> includes, String databaseUri) {

        public TargetInput(String name, String root, List<String> includes) {
            this(name, root, includes, null);
        }
    }

    /**
     * @param name      nome da entrada
     * @param path      caminho absoluto
     * @param directory é diretório
     * @param markers   indícios do conteúdo: git, maven, gradle, node, angular, java
     */
    public record BrowseEntry(String name, String path, boolean directory, List<String> markers) {
    }

    /**
     * @param path      diretório listado ({@code null} = pastas-base)
     * @param parent    diretório pai navegável ({@code null} no topo)
     * @param entries   conteúdo (diretórios primeiro)
     * @param truncated a listagem passou do limite
     */
    public record BrowseResult(String path, String parent, List<BrowseEntry> entries, boolean truncated) {
    }

    private final ProjectWorkspace workspace;
    private final AnalysisRepository analyses;
    private final TargetStore store;
    private final List<Path> allowedRoots;
    private volatile State state;

    public TargetService(TargetProperties properties, AgentHubProperties agentHubProperties, ProjectWorkspace workspace,
                         AnalysisRepository analyses) {
        this.workspace = workspace;
        this.analyses = analyses;
        this.store = new TargetStore(Path.of(properties.storeFile()));
        Path defaultRoot = Path.of(agentHubProperties.targetProject().root()).toAbsolutePath().normalize();
        List<String> configured = properties.allowedRoots().isEmpty()
                ? List.of(String.valueOf(defaultRoot.getParent() != null ? defaultRoot.getParent() : defaultRoot))
                : properties.allowedRoots();
        this.allowedRoots = configured.stream().map(TargetService::canonical).distinct().toList();
        this.state = store.load().orElseGet(() -> seed(agentHubProperties.targetProject(), defaultRoot));
        applyActive();
        log.info("event=targets.loaded file={} targets={} active={} allowedRoots={}",
                store.file(), state.targets().size(), workspace.root(), allowedRoots);
    }

    // ------------------------------------------------------------------ consultas

    public TargetOverview overview() {
        State current = state;
        List<TargetStatus> statuses = current.targets().stream()
                .sorted(Comparator.comparing(ProjectTarget::name, String.CASE_INSENSITIVE_ORDER))
                .map(t -> status(t, t.id().equals(current.activeId())))
                .toList();
        return new TargetOverview(current.activeId(), statuses,
                allowedRoots.stream().map(Path::toString).toList(), runningAnalyses(), store.file().toString());
    }

    public TargetStatus get(String id) {
        State current = state;
        return status(find(current, id), id.equals(current.activeId()));
    }

    /** Navegação pelas pastas permitidas (o navegador não informa o caminho absoluto de uma pasta local). */
    public BrowseResult browse(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            List<BrowseEntry> roots = allowedRoots.stream()
                    .filter(Files::isDirectory)
                    .map(r -> new BrowseEntry(r.toString(), r.toString(), true, markers(r)))
                    .toList();
            return new BrowseResult(null, null, roots, false);
        }
        Path dir = requireAllowedDirectory(rawPath);
        Path parent = dir.getParent() != null && isAllowed(dir.getParent()) ? dir.getParent() : null;
        List<BrowseEntry> entries = new ArrayList<>();
        boolean truncated = false;
        try (Stream<Path> children = Files.list(dir)) {
            List<Path> sorted = children
                    .filter(p -> !workspace.isExcludedDir(p) && !workspace.isSensitive(p))
                    .sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
            truncated = sorted.size() > MAX_BROWSE_ENTRIES;
            for (Path child : sorted.stream().limit(MAX_BROWSE_ENTRIES).toList()) {
                boolean directory = Files.isDirectory(child);
                entries.add(new BrowseEntry(child.getFileName().toString(), child.toString(), directory,
                        directory ? markers(child) : List.of()));
            }
        } catch (IOException e) {
            throw new InvalidTargetException("Não foi possível listar " + dir + ": " + e.getMessage());
        }
        return new BrowseResult(dir.toString(), parent != null ? parent.toString() : null, entries, truncated);
    }

    // ------------------------------------------------------------------ alterações

    public TargetStatus create(TargetInput input, boolean activate) {
        synchronized (workspace) {
            State current = state;
            if (current.targets().size() >= MAX_TARGETS) {
                throw new InvalidTargetException("Limite de " + MAX_TARGETS + " projetos cadastrados atingido.");
            }
            Instant now = Instant.now();
            ProjectTarget target = validated(UUID.randomUUID().toString().substring(0, 8), input, now, now, null);
            List<ProjectTarget> targets = new ArrayList<>(current.targets());
            targets.add(target);
            String activeId = current.activeId();
            if (activate) {
                requireIdle();
                activeId = target.id();
            }
            persist(new State(activeId, targets));
            log.info("event=target.created id={} root={} includes={} active={}", target.id(), target.root(),
                    target.includes(), activate);
            return get(target.id());
        }
    }

    public TargetStatus update(String id, TargetInput input) {
        synchronized (workspace) {
            State current = state;
            ProjectTarget existing = find(current, id);
            boolean active = id.equals(current.activeId());
            if (active) {
                requireIdle();
            }
            ProjectTarget updated = validated(id, input, existing.createdAt(), Instant.now(), existing.databaseUri());
            persist(new State(current.activeId(),
                    current.targets().stream().map(t -> t.id().equals(id) ? updated : t).toList()));
            log.info("event=target.updated id={} root={} includes={} database={}", id, updated.root(), updated.includes(),
                    updated.databaseUri() != null);
            return get(id);
        }
    }

    public void delete(String id) {
        synchronized (workspace) {
            State current = state;
            find(current, id);
            if (id.equals(current.activeId())) {
                throw new InvalidTargetException("Este é o projeto ativo. Ative outro projeto antes de removê-lo.");
            }
            persist(new State(current.activeId(), current.targets().stream().filter(t -> !t.id().equals(id)).toList()));
            log.info("event=target.deleted id={}", id);
        }
    }

    public TargetStatus activate(String id) {
        synchronized (workspace) {
            State current = state;
            ProjectTarget target = find(current, id);
            if (!id.equals(current.activeId())) {
                requireIdle();
            }
            TargetStatus status = status(target, true);
            if (!status.available()) {
                throw new InvalidTargetException(status.problem());
            }
            persist(new State(id, current.targets()));
            log.info("event=target.activated id={} root={} includes={}", id, target.root(), target.includes());
            return get(id);
        }
    }

    // ------------------------------------------------------------------ internos

    private State seed(AgentHubProperties.TargetProject config, Path defaultRoot) {
        Instant now = Instant.now();
        Path root = Files.isDirectory(defaultRoot) ? canonical(defaultRoot.toString()) : defaultRoot;
        String name = config.name() != null && !config.name().isBlank() ? config.name() : String.valueOf(root.getFileName());
        ProjectTarget initial = new ProjectTarget(UUID.randomUUID().toString().substring(0, 8), name, root.toString(),
                List.of(), now, now);
        State seeded = new State(initial.id(), List.of(initial));
        store.save(seeded);
        log.info("event=targets.seeded file={} root={}", store.file(), root);
        return seeded;
    }

    private void persist(State next) {
        store.save(next);
        state = next;
        applyActive();
    }

    /** Aplica o alvo ativo ao workspace; sem alvo ativo válido, mantém o da configuração. */
    private void applyActive() {
        State current = state;
        Optional<ProjectTarget> active = current.targets().stream()
                .filter(t -> t.id().equals(current.activeId()))
                .findFirst()
                .or(() -> current.targets().stream().findFirst());
        active.ifPresent(t -> workspace.switchTo(
                new WorkspaceTarget(t.id(), t.name(), Path.of(t.root()), t.includes(), t.databaseUri())));
    }

    private ProjectTarget validated(String id, TargetInput input, Instant createdAt, Instant updatedAt,
                                    String currentDatabaseUri) {
        if (input == null || input.root() == null || input.root().isBlank()) {
            throw new InvalidTargetException("Informe o diretório do projeto.");
        }
        Path root = requireAllowedDirectory(input.root());
        String name = input.name() != null && !input.name().isBlank() ? input.name().trim() : String.valueOf(root.getFileName());
        if (name.length() > MAX_NAME) {
            throw new InvalidTargetException("Nome com mais de " + MAX_NAME + " caracteres.");
        }
        return new ProjectTarget(id, name, root.toString(), normalizeIncludes(root, input.includes()), createdAt, updatedAt,
                DatabaseUri.resolve(input.databaseUri(), currentDatabaseUri));
    }

    /** Escopo relativo à raiz, sem duplicados nem caminhos já cobertos por outro include. */
    List<String> normalizeIncludes(Path root, List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<Path> includes = new LinkedHashSet<>();
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                continue;
            }
            Path resolved;
            try {
                resolved = root.resolve(unquote(value).replace('\\', '/')).toAbsolutePath().normalize();
            } catch (InvalidPathException e) {
                throw new InvalidTargetException("Caminho inválido no escopo: " + value);
            }
            if (!resolved.startsWith(root)) {
                throw new InvalidTargetException("Escopo fora do diretório do projeto: " + value);
            }
            if (resolved.equals(root)) {
                return List.of();
            }
            if (!Files.exists(resolved)) {
                throw new InvalidTargetException("Caminho do escopo não encontrado: " + value);
            }
            Path relative = root.relativize(resolved);
            for (Path part : relative) {
                if (workspace.isExcludedDir(part)) {
                    throw new InvalidTargetException("Diretório excluído da análise no escopo: " + value);
                }
            }
            if (workspace.isSensitive(resolved)) {
                throw new InvalidTargetException("Arquivo sensível não pode fazer parte do escopo: " + value);
            }
            includes.add(relative);
        }
        if (includes.size() > MAX_INCLUDES) {
            throw new InvalidTargetException("Máximo de " + MAX_INCLUDES + " itens no escopo.");
        }
        return includes.stream()
                .filter(p -> includes.stream().noneMatch(other -> !other.equals(p) && p.startsWith(other)))
                .map(p -> p.toString().replace('\\', '/'))
                .sorted()
                .toList();
    }

    private TargetStatus status(ProjectTarget t, boolean active) {
        String problem = null;
        Path root = Path.of(t.root());
        if (!Files.isDirectory(root)) {
            problem = "Diretório não encontrado: " + t.root();
        } else if (!isAllowed(root)) {
            problem = "Diretório fora das pastas permitidas (" + allowedRoots + "): " + t.root();
        } else {
            problem = t.includes().stream()
                    .filter(inc -> !Files.exists(root.resolve(inc)))
                    .findFirst()
                    .map(inc -> "Caminho do escopo não encontrado: " + inc)
                    .orElse(null);
        }
        return new TargetStatus(t.id(), t.name(), t.root(), t.includes(), t.createdAt(), t.updatedAt(), active,
                problem == null, problem, DatabaseUri.mask(t.databaseUri()));
    }

    private Path requireAllowedDirectory(String raw) {
        Path dir;
        try {
            dir = Path.of(unquote(raw)).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new InvalidTargetException("Caminho inválido: " + raw);
        }
        if (!Files.isDirectory(dir)) {
            throw new InvalidTargetException("Diretório não encontrado: " + dir);
        }
        Path real = canonical(dir.toString());
        if (!isAllowed(real)) {
            throw new InvalidTargetException("Diretório fora das pastas permitidas " + allowedRoots + ": " + real);
        }
        return real;
    }

    private boolean isAllowed(Path path) {
        Path real = canonical(path.toString());
        return allowedRoots.stream().anyMatch(real::startsWith);
    }

    private void requireIdle() {
        long running = runningAnalyses();
        if (running > 0) {
            throw new TargetBusyException(running);
        }
    }

    private long runningAnalyses() {
        return analyses.findAll().stream().filter(r -> !r.status().isFinished()).count();
    }

    private static ProjectTarget find(State state, String id) {
        return state.targets().stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new TargetNotFoundException(id));
    }

    private static List<String> markers(Path dir) {
        List<String> markers = new ArrayList<>();
        if (Files.exists(dir.resolve(".git"))) {
            markers.add("git");
        }
        if (Files.exists(dir.resolve("pom.xml"))) {
            markers.add("maven");
        }
        if (Files.exists(dir.resolve("build.gradle")) || Files.exists(dir.resolve("build.gradle.kts"))) {
            markers.add("gradle");
        }
        if (Files.exists(dir.resolve("angular.json"))) {
            markers.add("angular");
        } else if (Files.exists(dir.resolve("package.json"))) {
            markers.add("node");
        }
        if (Files.isDirectory(dir.resolve("src/main/java"))) {
            markers.add("java");
        }
        return markers;
    }

    /** Caminho real (resolve links e a grafia do disco) quando existe; senão, absoluto normalizado. */
    private static Path canonical(String raw) {
        Path path = Path.of(unquote(raw)).toAbsolutePath().normalize();
        try {
            return Files.exists(path) ? path.toRealPath() : path;
        } catch (IOException e) {
            return path;
        }
    }

    private static String unquote(String value) {
        String v = value.trim();
        return v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"") ? v.substring(1, v.length() - 1).trim() : v;
    }
}
