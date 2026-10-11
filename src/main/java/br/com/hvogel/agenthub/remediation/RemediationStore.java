package br.com.hvogel.agenthub.remediation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Arquivos da remediação, fora do projeto analisado:
 * <pre>
 * data/remediations/consents.json         consentimentos por projeto
 * data/remediations/&lt;id&gt;.json           propostas
 * data/remediations/&lt;id&gt;/backup/...     originais gravados antes de aplicar (podem conter segredos)
 * </pre>
 */
class RemediationStore {

    private static final Logger log = LoggerFactory.getLogger(RemediationStore.class);
    private static final Pattern ID = Pattern.compile("[0-9a-f]{12}");

    /**
     * @param projectRoot  raiz do projeto
     * @param acceptedAt   quando o usuário aceitou
     * @param termsVersion versão dos termos aceitos
     */
    record Consent(String projectRoot, Instant acceptedAt, int termsVersion) {
    }

    record ConsentState(List<Consent> consents) {
        ConsentState {
            consents = consents != null ? List.copyOf(consents) : List.of();
        }
    }

    private final Path dir;
    private final JsonMapper jsonMapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    RemediationStore(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
    }

    Path dir() {
        return dir;
    }

    static boolean isValidId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    List<RemediationProposal> loadAll() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<RemediationProposal> proposals = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                String name = file.getFileName().toString();
                if (!isValidId(name.substring(0, name.length() - ".json".length()))) {
                    continue;
                }
                try {
                    proposals.add(jsonMapper.readValue(Files.readString(file), RemediationProposal.class));
                } catch (IOException | RuntimeException e) {
                    log.warn("event=remediation.load_skip file={} error={}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao listar " + dir, e);
        }
        proposals.sort(Comparator.comparing(RemediationProposal::createdAt).reversed());
        return proposals;
    }

    void save(RemediationProposal proposal) {
        writeAtomic(dir.resolve(proposal.id() + ".json"), jsonMapper.writeValueAsString(proposal));
    }

    void delete(String id) {
        try {
            Files.deleteIfExists(dir.resolve(id + ".json"));
            Path backup = dir.resolve(id);
            if (Files.isDirectory(backup)) {
                try (Stream<Path> walk = Files.walk(backup)) {
                    for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(p);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao remover a proposta " + id, e);
        }
    }

    Path backupFile(String id, String relativePath) {
        Path backupRoot = dir.resolve(id).resolve("backup");
        Path file = backupRoot.resolve(relativePath).normalize();
        if (!file.startsWith(backupRoot)) {
            throw new IllegalArgumentException("Caminho de backup inválido: " + relativePath);
        }
        return file;
    }

    ConsentState loadConsents() {
        Path file = dir.resolve("consents.json");
        if (!Files.isRegularFile(file)) {
            return new ConsentState(List.of());
        }
        try {
            return jsonMapper.readValue(Files.readString(file), ConsentState.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler " + file, e);
        }
    }

    void saveConsents(ConsentState state) {
        writeAtomic(dir.resolve("consents.json"), jsonMapper.writeValueAsString(state));
    }

    /** Arquivo temporário no mesmo diretório + move: o destino nunca fica pela metade. */
    static void writeAtomic(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".agenthub-tmp");
            Files.writeString(tmp, content);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao gravar " + file, e);
        }
    }
}
