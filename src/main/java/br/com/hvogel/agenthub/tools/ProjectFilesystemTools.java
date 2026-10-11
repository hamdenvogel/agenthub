package br.com.hvogel.agenthub.tools;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

import br.com.hvogel.agenthub.context.ProjectWorkspace;
import br.com.hvogel.agenthub.context.SecretMasker;
import br.com.hvogel.agenthub.exception.ToolAccessDeniedException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Fonte de ferramentas {@code filesystem} do tipo {@code local}: leitura do projeto
 * analisado em processo, somente leitura e restrita à raiz (e ao escopo) do alvo ativo.
 *
 * <p>Não é um servidor MCP. Existe porque o servidor MCP oficial de filesystem exige
 * Node.js 18+; ao trocar a fonte para {@code type: mcp}, os agentes passam a usar o
 * servidor externo sem mudança de código.
 */
public class ProjectFilesystemTools {

    static final int MAX_LINES_PER_READ = 400;
    static final int MAX_SEARCH_MATCHES = 80;
    static final int MAX_FIND_RESULTS = 200;
    static final int MAX_LINE_LENGTH = 300;

    private final ProjectWorkspace workspace;

    public ProjectFilesystemTools(ProjectWorkspace workspace) {
        this.workspace = workspace;
    }

    @Tool(name = "fs_list_directory", description = "Lista arquivos e subdiretórios de um diretório do projeto analisado (somente leitura).")
    public String listDirectory(
            @ToolParam(description = "Caminho relativo à raiz do projeto. Use '.' para a raiz.") String path) {
        Path dir = workspace.resolve(path);
        if (!Files.isDirectory(dir)) {
            throw new ToolAccessDeniedException("Não é um diretório: " + path);
        }
        try (Stream<Path> children = Files.list(dir)) {
            List<String> entries = children
                    .filter(workspace::isVisible)
                    .sorted()
                    .map(p -> (Files.isDirectory(p) ? "[dir]  " : "[file] ") + workspace.relativize(p))
                    .toList();
            return entries.isEmpty() ? "(diretório vazio)" : String.join("\n", entries);
        } catch (IOException e) {
            throw new ToolAccessDeniedException("Falha ao listar " + path + ": " + e.getMessage());
        }
    }

    @Tool(name = "fs_read_file", description = "Lê um arquivo texto do projeto analisado com números de linha. "
            + "Valores de segredos aparecem mascarados. Máximo de 400 linhas por chamada; use startLine para paginar.")
    public String readFile(
            @ToolParam(description = "Caminho relativo à raiz do projeto.") String path,
            @ToolParam(description = "Primeira linha (1-based). Default 1.", required = false) Integer startLine,
            @ToolParam(description = "Quantidade de linhas (máx. 400). Default 400.", required = false) Integer maxLines) {
        Path file = workspace.resolve(path);
        try {
            List<String> lines = SecretMasker.mask(workspace.readText(file)).lines().toList();
            int from = Math.max(startLine != null ? startLine : 1, 1);
            int count = Math.clamp(maxLines != null ? maxLines : MAX_LINES_PER_READ, 1, MAX_LINES_PER_READ);
            int to = Math.min(from - 1 + count, lines.size());
            StringBuilder sb = new StringBuilder();
            sb.append("# ").append(workspace.relativize(file)).append(" (linhas ").append(from).append('-').append(to)
                    .append(" de ").append(lines.size()).append(")\n");
            for (int i = from - 1; i < to; i++) {
                sb.append(i + 1).append(": ").append(lines.get(i)).append('\n');
            }
            if (to < lines.size()) {
                sb.append("... (continua; use startLine=").append(to + 1).append(")\n");
            }
            return sb.toString();
        } catch (IOException e) {
            throw new ToolAccessDeniedException("Falha ao ler " + path + ": " + e.getMessage());
        }
    }

    @Tool(name = "fs_search_text", description = "Procura um texto ou regex (Java) nos arquivos do projeto analisado. "
            + "Retorna arquivo:linha: conteúdo. Máximo de 80 ocorrências.")
    public String searchText(
            @ToolParam(description = "Texto ou expressão regular a procurar.") String pattern,
            @ToolParam(description = "Glob para filtrar arquivos, ex.: '**/*.java' ou 'clientes-app/src/**/*.ts'. Default: todos.", required = false) String glob,
            @ToolParam(description = "Diretório inicial relativo à raiz. Default '.'.", required = false) String directory) {
        Pattern regex = compile(pattern);
        PathMatcher matcher = globMatcher(glob);
        Path start = workspace.resolve(directory);
        List<String> matches = new ArrayList<>();
        for (String relative : workspace.listFiles(start)) {
            if (matches.size() >= MAX_SEARCH_MATCHES) {
                break;
            }
            if (!matcher.matches(Path.of(relative))) {
                continue;
            }
            Path file = workspace.root().resolve(relative);
            if (workspace.isSensitive(file)) {
                continue;
            }
            try {
                List<String> lines = workspace.readText(file).lines().toList();
                for (int i = 0; i < lines.size() && matches.size() < MAX_SEARCH_MATCHES; i++) {
                    if (regex.matcher(lines.get(i)).find()) {
                        matches.add(relative + ":" + (i + 1) + ": " + truncate(lines.get(i).trim()));
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // binário, grande demais ou ilegível: segue a busca
            }
        }
        if (matches.isEmpty()) {
            return "Nenhuma ocorrência.";
        }
        String suffix = matches.size() >= MAX_SEARCH_MATCHES ? "\n... (limite de " + MAX_SEARCH_MATCHES + " ocorrências; refine a busca)" : "";
        return SecretMasker.mask(String.join("\n", matches)) + suffix;
    }

    @Tool(name = "fs_find_files", description = "Lista arquivos do projeto analisado que casam com um glob. Máximo de 200 resultados.")
    public String findFiles(
            @ToolParam(description = "Glob, ex.: '**/*Controller.java', '**/*.component.ts', '**/application*.properties'.") String glob) {
        PathMatcher matcher = globMatcher(glob);
        List<String> found = workspace.listFiles(workspace.root()).stream()
                .filter(f -> matcher.matches(Path.of(f)))
                .limit(MAX_FIND_RESULTS + 1L)
                .toList();
        if (found.isEmpty()) {
            return "Nenhum arquivo encontrado.";
        }
        if (found.size() > MAX_FIND_RESULTS) {
            return String.join("\n", found.subList(0, MAX_FIND_RESULTS)) + "\n... (mais resultados; refine o glob)";
        }
        return String.join("\n", found);
    }

    private static Pattern compile(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new ToolAccessDeniedException("pattern é obrigatório");
        }
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return Pattern.compile(Pattern.quote(pattern));
        }
    }

    private static PathMatcher globMatcher(String glob) {
        if (glob == null || glob.isBlank()) {
            return p -> true;
        }
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        if (glob.startsWith("**/")) {
            PathMatcher rootLevel = FileSystems.getDefault().getPathMatcher("glob:" + glob.substring(3));
            return p -> matcher.matches(p) || rootLevel.matches(p);
        }
        return matcher;
    }

    private static String truncate(String line) {
        return line.length() > MAX_LINE_LENGTH ? line.substring(0, MAX_LINE_LENGTH) + "..." : line;
    }
}
