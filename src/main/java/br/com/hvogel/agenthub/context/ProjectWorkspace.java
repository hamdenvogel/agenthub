package br.com.hvogel.agenthub.context;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import br.com.hvogel.agenthub.config.AgentHubProperties;
import br.com.hvogel.agenthub.exception.ToolAccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Fronteira de acesso ao projeto analisado. Toda leitura passa por aqui: os caminhos
 * são normalizados, precisam ficar dentro da raiz (e do escopo, quando houver) e
 * arquivos sensíveis são negados. Os agentes de análise só leem; a única escrita é a da
 * remediação aprovada pelo usuário, via {@link #resolveForWrite(String)}.
 *
 * <p>O alvo é trocado em tempo de execução por {@link #switchTo(WorkspaceTarget)};
 * quem troca garante que nenhuma análise está em andamento.
 */
@Component
public class ProjectWorkspace {

    private static final Pattern SENSITIVE_FILE = Pattern.compile(
            "(?i)^(\\.env(\\..*)?|.*\\.(pem|key|p12|pfx|jks|keystore|crt|cer|der)|id_rsa.*|id_ed25519.*|\\.npmrc|\\.netrc|credentials(\\..*)?)$");

    /** Descritores lidos mesmo fora do escopo, nos diretórios que levam até ele (stack e versões). */
    private static final Set<String> DESCRIPTORS = Set.of("pom.xml", "build.gradle", "build.gradle.kts",
            "settings.gradle", "settings.gradle.kts", "package.json", "angular.json", "tsconfig.json");

    private static final int MAX_GIT_PARENT_LEVELS = 4;

    private final Set<String> excludedDirs;
    private final long maxFileBytes;
    private volatile WorkspaceTarget target;

    public ProjectWorkspace(AgentHubProperties properties) {
        AgentHubProperties.TargetProject config = properties.targetProject();
        this.target = new WorkspaceTarget(null, config.name(), Path.of(config.root()), List.of());
        this.excludedDirs = Set.copyOf(config.excludedDirs().stream().map(d -> d.toLowerCase(Locale.ROOT)).toList());
        this.maxFileBytes = config.maxFileBytes();
    }

    public WorkspaceTarget target() {
        return target;
    }

    public void switchTo(WorkspaceTarget next) {
        this.target = next;
    }

    public Path root() {
        return target.root();
    }

    public String name() {
        return target.name();
    }

    public List<String> includes() {
        return target.includes();
    }

    /** URI do banco do projeto ativo (com senha; não registrar em log). */
    public Optional<String> database() {
        return target.database();
    }

    public long maxFileBytes() {
        return maxFileBytes;
    }

    public boolean exists() {
        return Files.isDirectory(root());
    }

    /**
     * Resolve um caminho relativo (ou absoluto dentro da raiz) com as regras de acesso.
     *
     * @throws ToolAccessDeniedException se o caminho sair da raiz ou do escopo, estiver excluído ou for sensível
     */
    public Path resolve(String relativePath) {
        WorkspaceTarget current = target;
        Path root = current.root();
        String candidate = relativePath == null || relativePath.isBlank() ? "." : relativePath.trim();
        Path resolved;
        try {
            resolved = root.resolve(candidate.replace('\\', '/')).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new ToolAccessDeniedException("Caminho inválido: " + relativePath);
        }
        if (!resolved.startsWith(root)) {
            throw new ToolAccessDeniedException("Caminho fora do projeto analisado: " + relativePath);
        }
        Path relative = root.relativize(resolved);
        for (Path part : relative) {
            if (excludedDirs.contains(part.toString().toLowerCase(Locale.ROOT))) {
                throw new ToolAccessDeniedException("Diretório excluído da análise: " + relativePath);
            }
        }
        if (!inScope(current, relative, Files.isDirectory(resolved))) {
            throw new ToolAccessDeniedException("Fora do escopo da análise (" + String.join(", ", current.includes())
                    + "): " + relativePath);
        }
        if (isSensitive(resolved)) {
            throw new ToolAccessDeniedException("Arquivo sensível não pode ser lido: " + relativePath);
        }
        return resolved;
    }

    /**
     * Destino de gravação de uma remediação: as mesmas regras de {@link #resolve(String)} (raiz, escopo,
     * exclusões, arquivos sensíveis), sem diretórios nem links simbólicos.
     */
    public Path resolveForWrite(String relativePath) {
        Path resolved = resolve(relativePath);
        if (Files.isDirectory(resolved) || Files.isSymbolicLink(resolved)) {
            throw new ToolAccessDeniedException("Destino de gravação inválido: " + relativePath);
        }
        return resolved;
    }

    /**
     * Valida um caminho absoluto recebido por ferramenta MCP (ex.: {@code repo_path} do servidor git):
     * precisa estar dentro da raiz ou ser o repositório git que contém a raiz.
     */
    public void requireWithinProject(String path) {
        Path candidate;
        try {
            candidate = Path.of(path.trim()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new ToolAccessDeniedException("Caminho inválido: " + path);
        }
        if (candidate.startsWith(root()) || enclosingGitRepository().filter(candidate::equals).isPresent()) {
            return;
        }
        throw new ToolAccessDeniedException("Caminho fora do projeto analisado: " + path);
    }

    /** Repositório git acima da raiz (quando a raiz é um subdiretório de um repositório). */
    public Optional<Path> enclosingGitRepository() {
        Path dir = root().getParent();
        for (int level = 0; dir != null && level < MAX_GIT_PARENT_LEVELS; level++, dir = dir.getParent()) {
            if (Files.exists(dir.resolve(".git"))) {
                return Optional.of(dir);
            }
        }
        return Optional.empty();
    }

    public String relativize(Path absolute) {
        return root().relativize(absolute.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    public boolean isExcludedDir(Path dir) {
        Path fileName = dir.getFileName();
        return fileName != null && excludedDirs.contains(fileName.toString().toLowerCase(Locale.ROOT));
    }

    public boolean isSensitive(Path file) {
        Path fileName = file.getFileName();
        return fileName != null && SENSITIVE_FILE.matcher(fileName.toString()).matches();
    }

    /** Entrada visível numa listagem: não excluída e dentro do escopo. */
    public boolean isVisible(Path absolute) {
        WorkspaceTarget current = target;
        Path normalized = absolute.toAbsolutePath().normalize();
        return normalized.startsWith(current.root()) && !isExcludedDir(normalized)
                && inScope(current, current.root().relativize(normalized), Files.isDirectory(normalized));
    }

    /**
     * Lista os arquivos regulares do projeto (caminhos relativos), respeitando exclusões e escopo.
     * Arquivos sensíveis aparecem no índice (o nome é informação útil), mas não podem ser lidos.
     */
    public List<String> listFiles(Path start) {
        if (!Files.isDirectory(start)) {
            return List.of();
        }
        WorkspaceTarget current = target;
        Path root = current.root();
        List<String> files = new ArrayList<>();
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(start)) {
                        return FileVisitResult.CONTINUE;
                    }
                    return isExcludedDir(dir) || !inScope(current, root.relativize(dir), true)
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && inScope(current, root.relativize(file), false)) {
                        files.add(root.relativize(file).toString().replace('\\', '/'));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        files.sort(String::compareTo);
        return files;
    }

    /** Lê um arquivo texto do projeto, limitado a {@link #maxFileBytes()}. */
    public String readText(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new ToolAccessDeniedException("Não é um arquivo regular: " + relativize(file));
        }
        if (Files.size(file) > maxFileBytes) {
            throw new ToolAccessDeniedException("Arquivo maior que o limite de " + maxFileBytes + " bytes: " + relativize(file));
        }
        return Files.readString(file);
    }

    /**
     * Dentro do escopo: caminho sob um include; diretório que leva a um include (navegação);
     * ou descritor de build num desses diretórios.
     */
    static boolean inScope(WorkspaceTarget target, Path relative, boolean directory) {
        if (!target.scoped() || relative.toString().isEmpty()) {
            return true;
        }
        Path parent = relative.getParent() != null ? relative.getParent() : Path.of("");
        for (String include : target.includes()) {
            Path inc = Path.of(include);
            if (relative.startsWith(inc)) {
                return true;
            }
            if (directory && inc.startsWith(relative)) {
                return true;
            }
            if (!directory && DESCRIPTORS.contains(String.valueOf(relative.getFileName()))
                    && (parent.toString().isEmpty() || inc.startsWith(parent))) {
                return true;
            }
        }
        return false;
    }
}
