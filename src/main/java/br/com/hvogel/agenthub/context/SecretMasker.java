package br.com.hvogel.agenthub.context;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mascara valores de segredos antes que o conteúdo saia do processo (para o Claude ou
 * para logs). O nome da chave é preservado para que o Security Agent ainda consiga
 * apontar "segredo hardcoded" como achado.
 */
public final class SecretMasker {

    private static final String SECRET_WORDS =
            "password|passwd|pwd|secret|token|api[-_.]?key|apikey|private[-_.]?key|credential|client[-_.]?secret|access[-_.]?key"
            + "|signing[-_.]?key|senha|segredo|assinatura|chave[-_.]?(?:de[-_.]?)?(?:secreta|privada|api|acesso|jwt)";

    /**
     * {@code chave=valor} ou {@code chave: valor} (properties, yml, env), inclusive com prefixo
     * na linha (diff {@code +}, numeração de linha).
     */
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?im)((?<![\\w.\\-\\[\\]])[\\w.\\-\\[\\]]*(?:" + SECRET_WORDS + ")[\\w.\\-\\[\\]]*\\s*[:=]\\s*)([^\\s#].*?)\\s*$");

    /** Valor com cara de código ({@code getPassword();}, {@code string;}) não é segredo de configuração. */
    private static final Pattern CODE_LIKE_VALUE = Pattern.compile(".*[(){};].*|^(string|number|boolean|any)\\b.*");

    /** Atribuição de literal string em código (Java/TS): {@code password = "valor"}. */
    private static final Pattern CODE_LITERAL = Pattern.compile(
            "(?i)(\\b\\w*(?:" + SECRET_WORDS + ")\\w*\\s*[:=]\\s*[\"'])([^\"']{4,})([\"'])");

    /** Tokens com formato conhecido (JWT, chaves Anthropic/OpenAI, AWS). */
    private static final Pattern KNOWN_TOKENS = Pattern.compile(
            "\\b(eyJ[\\w-]{10,}\\.[\\w-]{10,}\\.[\\w-]{10,}|sk-[\\w-]{20,}|AKIA[0-9A-Z]{16})\\b");

    private SecretMasker() {
    }

    public static String mask(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        String masked = replaceKeyValues(content);
        masked = CODE_LITERAL.matcher(masked).replaceAll(m -> m.group(2).startsWith("***MASKED")
                ? Matcher.quoteReplacement(m.group(0))
                : Matcher.quoteReplacement(m.group(1) + placeholder(m.group(2)) + m.group(3)));
        return KNOWN_TOKENS.matcher(masked).replaceAll(m -> Matcher.quoteReplacement(placeholder(m.group(1))));
    }

    private static String replaceKeyValues(String content) {
        return KEY_VALUE.matcher(content).replaceAll(m -> {
            String value = m.group(2);
            boolean keep = value.trim().startsWith("${")
                    ? isPlaceholderReference(value)
                    : value.startsWith("***MASKED") || value.startsWith("\"") || value.startsWith("'")
                            || CODE_LIKE_VALUE.matcher(value).matches();
            if (keep) {
                return Matcher.quoteReplacement(m.group(0));
            }
            return Matcher.quoteReplacement(m.group(1) + placeholder(value));
        });
    }

    /** {@code ${ENV_VAR}} não é segredo: é exatamente a prática recomendada. */
    private static boolean isPlaceholderReference(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("${") && trimmed.endsWith("}") && !trimmed.contains(":");
    }

    private static String placeholder(String value) {
        return "***MASKED(" + value.length() + " chars)***";
    }
}
