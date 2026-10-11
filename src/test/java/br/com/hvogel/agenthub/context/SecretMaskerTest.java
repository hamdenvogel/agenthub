package br.com.hvogel.agenthub.context;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretMaskerTest {

    @Test
    void masksPropertiesAndYamlSecretsButKeepsKeys() {
        String masked = SecretMasker.mask("""
                spring.datasource.password=s3nh4Forte
                jwt.secret: abcdefghijklmnop
                spring.datasource.url=jdbc:postgresql://localhost/db
                """);
        assertThat(masked)
                .doesNotContain("s3nh4Forte").doesNotContain("abcdefghijklmnop")
                .contains("spring.datasource.password=***MASKED(10 chars)***")
                .contains("jwt.secret: ***MASKED")
                .contains("jdbc:postgresql://localhost/db");
    }

    @Test
    void keepsEnvironmentPlaceholdersButMasksDefaults() {
        assertThat(SecretMasker.mask("app.password=${DB_PASSWORD}")).isEqualTo("app.password=${DB_PASSWORD}");
        assertThat(SecretMasker.mask("app.password=${DB_PASSWORD:padrao123}")).doesNotContain("padrao123");
    }

    @Test
    void masksStringLiteralsInCodeAndKnownTokenFormats() {
        String masked = SecretMasker.mask("""
                private String secretKey = "chave-muito-secreta";
                String t = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnopqrstu";
                """);
        assertThat(masked).doesNotContain("chave-muito-secreta").doesNotContain("eyJhbGciOiJIUzI1NiJ9.eyJ");
    }

    @Test
    void masksPrefixedLinesFromDiffsAndNumberedOutput() {
        assertThat(SecretMasker.mask("+jwt.secret=minha-chave")).doesNotContain("minha-chave");
        assertThat(SecretMasker.mask("12: spring.mail.password=abc12345")).doesNotContain("abc12345").startsWith("12: ");
    }

    @Test
    void masksPortugueseSecretNames() {
        assertThat(SecretMasker.mask("security.jwt.chave-assinatura=YSBsZW1icmUgZSBicmFuY2E="))
                .doesNotContain("YSBsZW1icmUgZSBicmFuY2E=");
        assertThat(SecretMasker.mask("app.senha=minhaSenha1")).doesNotContain("minhaSenha1");
        assertThat(SecretMasker.mask("private String chaveSecreta = \"abc123xyz\";")).doesNotContain("abc123xyz");
        assertThat(SecretMasker.mask("app.chave-primaria=id")).isEqualTo("app.chave-primaria=id");
    }

    @Test
    void doesNotHideCodeAndIsIdempotent() {
        String code = """
                String token = jwtService.generateToken(user);
                password: string;
                """;
        assertThat(SecretMasker.mask(code)).isEqualTo(code);

        String once = SecretMasker.mask("db.password=segredo1");
        assertThat(SecretMasker.mask(once)).isEqualTo(once);
    }

    @Test
    void leavesOrdinaryContentUntouched() {
        String code = "public class ClienteService { int total = 10; }";
        assertThat(SecretMasker.mask(code)).isEqualTo(code);
    }
}
