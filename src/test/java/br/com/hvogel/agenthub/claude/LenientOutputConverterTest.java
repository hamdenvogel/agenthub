package br.com.hvogel.agenthub.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class LenientOutputConverterTest {

    record Draft(String summary, List<String> items) {
    }

    private final LenientOutputConverter<Draft> converter = new LenientOutputConverter<>(Draft.class);

    @Test
    void parsesPlainJson() {
        assertThat(converter.convert("{\"summary\":\"ok\",\"items\":[\"a\"]}").summary()).isEqualTo("ok");
    }

    @Test
    void toleratesProseAroundTheJsonObject() {
        String text = "Com base na análise do projeto, segue o resultado:\n{\"summary\":\"resumo\",\"items\":[\"x\",\"y\"]}\nFim.";

        Draft draft = converter.convert(text);

        assertThat(draft.summary()).isEqualTo("resumo");
        assertThat(draft.items()).containsExactly("x", "y");
    }

    @Test
    void failsWhenThereIsNoJsonObject() {
        assertThatThrownBy(() -> converter.convert("Com base na análise, não encontrei nada.")).isInstanceOf(RuntimeException.class);
        assertThat(LenientOutputConverter.extractJsonObject("sem json")).isNull();
    }
}
