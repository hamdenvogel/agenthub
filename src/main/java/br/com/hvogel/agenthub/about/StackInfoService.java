package br.com.hvogel.agenthub.about;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.modelcontextprotocol.client.McpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootVersion;
import org.springframework.core.SpringVersion;
import org.springframework.stereotype.Service;

/**
 * Versões da stack em execução: bibliotecas do backend, JVM, Node.js do PATH (usado pelo {@code ng serve} e pelos
 * servidores MCP via {@code npx}) e sistema operacional. Sem custo de LLM.
 */
@Service
public class StackInfoService {

    private static final Logger log = LoggerFactory.getLogger(StackInfoService.class);

    private static final Pattern JAR_VERSION = Pattern.compile("-(\\d+\\.\\d+[^/\\\\]*?)\\.jar$");
    private static final long NODE_TIMEOUT_SECONDS = 5;

    public record StackComponent(String id, String name, String version, String detail) {
    }

    public record StackInfo(List<StackComponent> backend, List<StackComponent> runtime, Instant generatedAt) {
    }

    private final String claudeModel;
    private volatile String nodeVersion;
    private volatile boolean nodeResolved;

    public StackInfoService(@Value("${spring.ai.anthropic.chat.options.model:}") String claudeModel) {
        this.claudeModel = claudeModel;
    }

    public StackInfo info() {
        List<StackComponent> backend = List.of(
                new StackComponent("spring-boot", "Spring Boot", SpringBootVersion.getVersion(), null),
                new StackComponent("spring-framework", "Spring Framework", SpringVersion.getVersion(), null),
                new StackComponent("spring-ai", "Spring AI", versionOf(ChatClient.class), "Anthropic + MCP client"),
                new StackComponent("mcp-sdk", "MCP Java SDK", versionOf(McpClient.class), null),
                new StackComponent("claude-model", "Modelo Claude", blankToNull(claudeModel),
                        "spring.ai.anthropic.chat.options.model"));
        Runtime.Version java = Runtime.version();
        List<StackComponent> runtime = List.of(
                new StackComponent("java", "Java", java.toString(),
                        System.getProperty("java.vendor") + " · " + System.getProperty("java.vm.name")),
                new StackComponent("node", "Node.js", nodeVersion(), "PATH do backend: ng serve e servidores MCP (npx)"),
                new StackComponent("os", "Sistema operacional",
                        System.getProperty("os.name") + " " + System.getProperty("os.version"), System.getProperty("os.arch")));
        return new StackInfo(backend, runtime, Instant.now());
    }

    /** Versão do jar que contém a classe: manifest ou, na falta dele, o nome do arquivo ({@code nome-1.2.3.jar}). */
    static String versionOf(Class<?> type) {
        String version = type.getPackage() != null ? type.getPackage().getImplementationVersion() : null;
        if (version != null && !version.isBlank()) {
            return version;
        }
        try {
            CodeSource source = type.getProtectionDomain().getCodeSource();
            return source != null ? versionFromJarName(source.getLocation().getPath()) : null;
        } catch (SecurityException e) {
            return null;
        }
    }

    static String versionFromJarName(String path) {
        Matcher matcher = JAR_VERSION.matcher(path == null ? "" : path);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String nodeVersion() {
        if (!nodeResolved) {
            synchronized (this) {
                if (!nodeResolved) {
                    nodeVersion = detectNode();
                    nodeResolved = true;
                }
            }
        }
        return nodeVersion;
    }

    private static String detectNode() {
        try {
            Process process = new ProcessBuilder("node", "-v").redirectErrorStream(true).start();
            if (!process.waitFor(NODE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            try (InputStream out = process.getInputStream()) {
                String text = new String(out.readAllBytes(), StandardCharsets.UTF_8).trim();
                return process.exitValue() == 0 && text.startsWith("v") ? text.substring(1) : null;
            }
        } catch (IOException e) {
            log.info("event=stack.node_unavailable error={}", e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
