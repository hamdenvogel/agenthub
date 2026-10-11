package br.com.hvogel.agenthub.context;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Projeto efetivamente analisado: raiz absoluta e, opcionalmente, o escopo
 * (subdiretórios ou arquivos relativos à raiz). Escopo vazio = projeto inteiro.
 *
 * @param id          id do alvo cadastrado ({@code null} quando vem da configuração)
 * @param name        nome amigável
 * @param root        raiz absoluta e normalizada
 * @param includes    caminhos relativos à raiz, separados por {@code /}
 * @param databaseUri banco do projeto (com senha) para a fonte {@code database}; {@code null} = sem banco
 */
public record WorkspaceTarget(String id, String name, Path root, List<String> includes, String databaseUri) {

    public WorkspaceTarget {
        root = root.toAbsolutePath().normalize();
        name = name != null && !name.isBlank() ? name : String.valueOf(root.getFileName());
        includes = includes != null ? List.copyOf(includes) : List.of();
        databaseUri = databaseUri != null && !databaseUri.isBlank() ? databaseUri : null;
    }

    public WorkspaceTarget(String id, String name, Path root, List<String> includes) {
        this(id, name, root, includes, null);
    }

    public boolean scoped() {
        return !includes.isEmpty();
    }

    public Optional<String> database() {
        return Optional.ofNullable(databaseUri);
    }

    @Override
    public String toString() {
        return "WorkspaceTarget[id=" + id + ", name=" + name + ", root=" + root + ", includes=" + includes
                + ", database=" + (databaseUri != null) + "]";
    }
}
