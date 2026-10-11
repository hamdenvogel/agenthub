package br.com.hvogel.agenthub.remediation;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import br.com.hvogel.agenthub.exception.RemediationException;

/**
 * Aplica as edições {@code search/replace} de um {@link FixPlan.FileChange} ao conteúdo atual.
 *
 * <p>O agente vê os arquivos com segredos mascarados ({@code ***MASKED(n chars)***}). No {@code search}
 * o marcador casa com exatamente {@code n} caracteres do valor real, então o modelo consegue substituir
 * um segredo sem nunca conhecê-lo; no {@code replace} o marcador é recusado (gravaria o texto mascarado).
 */
final class EditApplier {

    static final Pattern MASK = Pattern.compile("\\*\\*\\*MASKED\\((\\d+) chars\\)\\*\\*\\*");

    private EditApplier() {
    }

    static String apply(String original, List<FixPlan.Edit> edits) {
        String eol = original.contains("\r\n") ? "\r\n" : "\n";
        String text = original;
        for (int i = 0; i < edits.size(); i++) {
            FixPlan.Edit edit = edits.get(i);
            String label = "edição " + (i + 1);
            if (edit.search() == null || edit.search().isEmpty()) {
                throw new RemediationException(label + " sem o trecho 'search'.");
            }
            String replace = edit.replace() != null ? edit.replace() : "";
            if (replace.contains("***MASKED")) {
                throw new RemediationException(label + ": o 'replace' contém um valor mascarado; use um placeholder "
                        + "como ${NOME_DA_VARIAVEL} em vez do segredo.");
            }
            Matcher matcher = toPattern(withEol(edit.search(), eol)).matcher(text);
            if (!matcher.find()) {
                throw new RemediationException(label + ": trecho 'search' não encontrado no arquivo atual "
                        + "(copie o texto exato, com indentação e sem os números de linha).");
            }
            int start = matcher.start();
            int end = matcher.end();
            if (matcher.find()) {
                throw new RemediationException(label + ": trecho 'search' aparece mais de uma vez; inclua linhas de "
                        + "contexto para torná-lo único.");
            }
            text = text.substring(0, start) + withEol(replace, eol) + text.substring(end);
        }
        return text;
    }

    static Pattern toPattern(String search) {
        StringBuilder regex = new StringBuilder();
        Matcher mask = MASK.matcher(search);
        int last = 0;
        while (mask.find()) {
            regex.append(Pattern.quote(search.substring(last, mask.start())))
                    .append("[^\\r\\n]{").append(mask.group(1)).append('}');
            last = mask.end();
        }
        regex.append(Pattern.quote(search.substring(last)));
        return Pattern.compile(regex.toString());
    }

    private static String withEol(String value, String eol) {
        String normalized = value.replace("\r\n", "\n");
        return "\n".equals(eol) ? normalized : normalized.replace("\n", eol);
    }
}
