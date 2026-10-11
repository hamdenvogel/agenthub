package br.com.hvogel.agenthub.target;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

import br.com.hvogel.agenthub.exception.InvalidTargetException;

/**
 * URI de conexão do banco de um projeto ({@code postgresql://usuario:senha@host:porta/banco}). A senha é gravada em
 * {@code data/targets.json}, mas nunca sai pela API: {@link #mask(String)} a troca por {@value #MASK}.
 */
final class DatabaseUri {

    static final String MASK = "***";
    static final int MAX_LENGTH = 500;

    private static final Set<String> SCHEMES = Set.of("postgresql", "postgres");

    private DatabaseUri() {
    }

    /**
     * Valor a gravar para o projeto, a partir do que veio da UI.
     *
     * @param input   valor recebido: {@code null} mantém o atual; vazio remove; igual à versão mascarada do atual
     *                mantém o atual (a UI devolve o que exibiu)
     * @param current URI gravada hoje ({@code null} se não houver)
     */
    static String resolve(String input, String current) {
        if (input == null) {
            return current;
        }
        String value = input.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (current != null && value.equals(mask(current))) {
            return current;
        }
        if (value.contains(":" + MASK + "@")) {
            throw new InvalidTargetException("Digite a senha do banco novamente ao alterar a URI (o " + MASK
                    + " só mantém a senha quando a URI não muda).");
        }
        return validate(value);
    }

    static String validate(String value) {
        if (value.length() > MAX_LENGTH) {
            throw new InvalidTargetException("URI do banco com mais de " + MAX_LENGTH + " caracteres.");
        }
        if (value.chars().anyMatch(Character::isWhitespace)) {
            throw new InvalidTargetException("URI do banco não pode ter espaços (codifique caracteres especiais da senha, ex.: %40 para @).");
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new InvalidTargetException("URI do banco inválida (codifique caracteres especiais da senha, ex.: %40 para @).");
        }
        if (uri.getScheme() == null || !SCHEMES.contains(uri.getScheme().toLowerCase())) {
            throw new InvalidTargetException("URI do banco deve começar com postgresql:// (ex.: postgresql://usuario:senha@localhost:5432/banco).");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new InvalidTargetException("Informe o servidor na URI do banco (ex.: postgresql://usuario:senha@localhost:5432/banco).");
        }
        String path = uri.getRawPath();
        if (path == null || path.length() <= 1 || path.indexOf('/', 1) >= 0) {
            throw new InvalidTargetException("Informe o nome do banco na URI (ex.: postgresql://usuario:senha@localhost:5432/banco).");
        }
        return value;
    }

    /** URI com a senha trocada por {@value #MASK}; {@code null} se não houver URI. */
    static String mask(String uri) {
        if (uri == null) {
            return null;
        }
        int scheme = uri.indexOf("://");
        if (scheme < 0) {
            return uri;
        }
        int authorityEnd = uri.indexOf('/', scheme + 3);
        int at = uri.lastIndexOf('@', authorityEnd < 0 ? uri.length() - 1 : authorityEnd);
        if (at < scheme) {
            return uri;
        }
        int colon = uri.indexOf(':', scheme + 3);
        if (colon < 0 || colon > at) {
            return uri;
        }
        return uri.substring(0, colon + 1) + MASK + uri.substring(at);
    }
}
