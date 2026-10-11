package br.com.hvogel.agenthub.target;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.hvogel.agenthub.exception.InvalidTargetException;
import org.junit.jupiter.api.Test;

class DatabaseUriTest {

    private static final String URI = "postgresql://leitor:s3cr%40ta@localhost:5435/meusservicos?sslmode=disable&x=a@b";

    @Test
    void maskHidesOnlyThePassword() {
        assertThat(DatabaseUri.mask(URI)).isEqualTo("postgresql://leitor:***@localhost:5435/meusservicos?sslmode=disable&x=a@b");
        assertThat(DatabaseUri.mask("postgresql://leitor@localhost/db")).isEqualTo("postgresql://leitor@localhost/db");
        assertThat(DatabaseUri.mask("postgresql://localhost/db")).isEqualTo("postgresql://localhost/db");
        assertThat(DatabaseUri.mask(null)).isNull();
    }

    @Test
    void resolveKeepsRemovesOrReplaces() {
        assertThat(DatabaseUri.resolve(null, URI)).isEqualTo(URI);
        assertThat(DatabaseUri.resolve("  ", URI)).isNull();
        assertThat(DatabaseUri.resolve(DatabaseUri.mask(URI), URI)).isEqualTo(URI);
        assertThat(DatabaseUri.resolve(" postgresql://u:p@h:1/outro ", URI)).isEqualTo("postgresql://u:p@h:1/outro");
    }

    @Test
    void maskedPasswordWithAnotherUriIsRejected() {
        assertThatThrownBy(() -> DatabaseUri.resolve("postgresql://leitor:***@outro:5435/meusservicos", URI))
                .isInstanceOf(InvalidTargetException.class).hasMessageContaining("senha");
        assertThatThrownBy(() -> DatabaseUri.resolve("postgresql://leitor:***@localhost:5435/meusservicos", null))
                .isInstanceOf(InvalidTargetException.class);
    }

    @Test
    void invalidUrisAreRejectedWithoutEchoingThem() {
        for (String invalid : new String[] {"mysql://u:segredo@h/db", "postgresql://u:segredo@/db", "postgresql://u:segredo@h",
                "postgresql://u:segredo@h/a/b", "postgresql://u:se gredo@h/db", "postgresql://u:segredo@h/db" + "x".repeat(500)}) {
            assertThatThrownBy(() -> DatabaseUri.validate(invalid))
                    .isInstanceOf(InvalidTargetException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("segredo"));
        }
    }
}
