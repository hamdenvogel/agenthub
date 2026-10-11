package br.com.hvogel.agenthub.target;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Persistência dos alvos num arquivo JSON (sem banco). A gravação é atômica: arquivo
 * temporário + move, para não corromper o estado se o processo cair no meio.
 */
class TargetStore {

    /**
     * @param activeId id do alvo ativo
     * @param targets  alvos cadastrados
     */
    record State(String activeId, List<ProjectTarget> targets) {
        State {
            targets = targets != null ? List.copyOf(targets) : List.of();
        }
    }

    private final Path file;
    private final JsonMapper jsonMapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    TargetStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    Path file() {
        return file;
    }

    Optional<State> load() {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(jsonMapper.readValue(Files.readString(file), State.class));
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler " + file, e);
        }
    }

    void save(State state) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, jsonMapper.writeValueAsString(state));
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
