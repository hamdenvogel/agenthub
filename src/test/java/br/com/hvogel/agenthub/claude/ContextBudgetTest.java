package br.com.hvogel.agenthub.claude;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContextBudgetTest {

    @Test
    void defaultBudgetIsCappedByWhatIsLeftAfterMaxTokens() {
        ContextBudget fromDefault = ContextBudget.unlimited();
        fromDefault.resolve(120_000, 200_000 - 8_192);
        assertThat(fromDefault.limit()).isEqualTo(120_000);

        ContextBudget tooLarge = new ContextBudget(250_000);
        tooLarge.resolve(120_000, 200_000 - 8_192);
        assertThat(tooLarge.limit()).isEqualTo(191_808);
    }

    @Test
    void fixedPartMustFitAndResultsAreAccumulatedOnTop() {
        ContextBudget budget = new ContextBudget(1_000);
        assertThat(budget.reserve(1_200)).isFalse();
        assertThat(budget.reserve(400)).isTrue();

        assertThat(budget.admit("a".repeat(700))).hasSize(700);
        assertThat(budget.used()).isEqualTo(600);
        assertThat(budget.exhausted()).isFalse();

        String cut = budget.admit("b".repeat(7_000));
        assertThat(cut).contains("resultado cortado").hasSizeLessThan(7_000);
        assertThat(budget.exhausted()).isTrue();
        assertThat(budget.used()).isLessThanOrEqualTo(1_000);
    }

    @Test
    void almostFullBudgetReturnsOnlyTheExhaustedMessage() {
        ContextBudget budget = new ContextBudget(1_000);
        budget.reserve(950);
        assertThat(budget.admit("x".repeat(1_000))).startsWith("Context window desta execução esgotada");
        assertThat(budget.exhausted()).isTrue();
    }

    @Test
    void unresolvedUnlimitedBudgetNeverCuts() {
        ContextBudget budget = ContextBudget.unlimited();
        assertThat(budget.admit("y".repeat(500_000))).hasSize(500_000);
        assertThat(budget.exhausted()).isFalse();
        assertThat(budget.limit()).isZero();
    }
}
