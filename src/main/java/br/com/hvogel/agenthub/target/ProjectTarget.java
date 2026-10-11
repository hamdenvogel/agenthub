package br.com.hvogel.agenthub.target;

import java.time.Instant;
import java.util.List;

/**
 * Projeto-alvo cadastrado: o que o AgentHub analisa.
 *
 * @param id          identificador
 * @param name        nome amigável
 * @param root        raiz absoluta
 * @param includes    subdiretórios/arquivos relativos à raiz (vazio = projeto inteiro)
 * @param createdAt   criação
 * @param updatedAt   última alteração
 * @param databaseUri banco do projeto para a fonte {@code database} (com senha; nunca devolvida pela API);
 *                    {@code null} = projeto sem banco
 */
public record ProjectTarget(String id, String name, String root, List<String> includes, Instant createdAt,
                            Instant updatedAt, String databaseUri) {

    public ProjectTarget {
        includes = includes != null ? List.copyOf(includes) : List.of();
        databaseUri = databaseUri != null && !databaseUri.isBlank() ? databaseUri : null;
    }

    public ProjectTarget(String id, String name, String root, List<String> includes, Instant createdAt,
                         Instant updatedAt) {
        this(id, name, root, includes, createdAt, updatedAt, null);
    }

    @Override
    public String toString() {
        return "ProjectTarget[id=" + id + ", name=" + name + ", root=" + root + ", includes=" + includes
                + ", databaseUri=" + DatabaseUri.mask(databaseUri) + "]";
    }
}
