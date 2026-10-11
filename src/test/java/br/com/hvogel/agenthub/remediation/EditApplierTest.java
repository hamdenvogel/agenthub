package br.com.hvogel.agenthub.remediation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import br.com.hvogel.agenthub.context.SecretMasker;
import br.com.hvogel.agenthub.exception.RemediationException;
import org.junit.jupiter.api.Test;

class EditApplierTest {

    @Test
    void replacesUniqueSnippetKeepingCrlf() {
        String original = "class A {\r\n    int x = 1;\r\n    int y = 2;\r\n}\r\n";

        String updated = EditApplier.apply(original, List.of(new FixPlan.Edit("    int x = 1;\n    int y = 2;", "    int x = 10;\n    int y = 2;")));

        assertThat(updated).isEqualTo("class A {\r\n    int x = 10;\r\n    int y = 2;\r\n}\r\n");
    }

    @Test
    void maskedValueInSearchMatchesTheRealSecretWithoutRevealingIt() {
        String original = "server.port=8080\njwt.secret=SuperSecret123\nfoo=bar\n";
        String maskedLine = SecretMasker.mask("jwt.secret=SuperSecret123");
        assertThat(maskedLine).doesNotContain("SuperSecret123");

        String updated = EditApplier.apply(original, List.of(new FixPlan.Edit(maskedLine, "jwt.secret=${JWT_SECRET}")));

        assertThat(updated).isEqualTo("server.port=8080\njwt.secret=${JWT_SECRET}\nfoo=bar\n");
    }

    @Test
    void rejectsMissingAmbiguousOrMaskedReplacements() {
        String original = "a=1\nb=1\n";

        assertThatThrownBy(() -> EditApplier.apply(original, List.of(new FixPlan.Edit("c=1", "c=2"))))
                .isInstanceOf(RemediationException.class).hasMessageContaining("não encontrado");
        assertThatThrownBy(() -> EditApplier.apply(original, List.of(new FixPlan.Edit("=1", "=2"))))
                .isInstanceOf(RemediationException.class).hasMessageContaining("mais de uma vez");
        assertThatThrownBy(() -> EditApplier.apply(original, List.of(new FixPlan.Edit("a=1", "a=***MASKED(5 chars)***"))))
                .isInstanceOf(RemediationException.class).hasMessageContaining("mascarado");
    }

    @Test
    void editsApplyInSequence() {
        String updated = EditApplier.apply("one\ntwo\n", List.of(new FixPlan.Edit("one", "uno"), new FixPlan.Edit("uno\ntwo", "uno\ndos")));

        assertThat(updated).isEqualTo("uno\ndos\n");
    }
}
