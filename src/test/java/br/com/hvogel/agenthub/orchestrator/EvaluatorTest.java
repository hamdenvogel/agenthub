package br.com.hvogel.agenthub.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import br.com.hvogel.agenthub.model.AgentAnalysis.FindingDraft;
import br.com.hvogel.agenthub.model.AgentResult;
import br.com.hvogel.agenthub.model.AgentRole;
import br.com.hvogel.agenthub.model.AgentStatus;
import br.com.hvogel.agenthub.model.AgentTask;
import br.com.hvogel.agenthub.model.Evaluation;
import br.com.hvogel.agenthub.model.Severity;
import br.com.hvogel.agenthub.support.Fixtures;
import org.junit.jupiter.api.Test;

class EvaluatorTest {

    private final Evaluator evaluator = new Evaluator(Fixtures.properties());
    private final AgentTask backendTask = AgentTask.initial(AgentRole.BACKEND, "objetivo", List.of(), List.of());

    private Evaluation evaluate(AgentResult result, int followUpsUsed) {
        Map<AgentRole, AgentResult> results = new EnumMap<>(AgentRole.class);
        results.put(AgentRole.BACKEND, result);
        Map<AgentRole, Integer> used = new EnumMap<>(AgentRole.class);
        used.put(AgentRole.BACKEND, followUpsUsed);
        return evaluator.evaluate(List.of(backendTask), results, used);
    }

    @Test
    void goodResultIsSufficient() {
        Evaluation evaluation = evaluate(Fixtures.success(AgentRole.BACKEND, 0.8,
                Fixtures.finding("Problema", Severity.HIGH, "A.java")), 0);
        assertThat(evaluation.sufficient()).isTrue();
        assertThat(evaluation.followUps()).isEmpty();
    }

    @Test
    void lowConfidenceTriggersFollowUpWithFeedback() {
        Evaluation evaluation = evaluate(Fixtures.success(AgentRole.BACKEND, 0.2,
                Fixtures.finding("Problema", Severity.HIGH, "A.java")), 0);
        assertThat(evaluation.sufficient()).isFalse();
        assertThat(evaluation.followUps()).singleElement().satisfies(t -> {
            assertThat(t.role()).isEqualTo(AgentRole.BACKEND);
            assertThat(t.round()).isEqualTo(2);
            assertThat(t.feedback()).contains("confiança");
        });
    }

    @Test
    void findingsWithoutEvidenceTriggerFollowUp() {
        FindingDraft noEvidence = Fixtures.finding("Sem prova", Severity.MEDIUM, null);
        Evaluation evaluation = evaluate(Fixtures.success(AgentRole.BACKEND, 0.9, noEvidence, noEvidence), 0);
        assertThat(evaluation.followUps()).singleElement()
                .satisfies(t -> assertThat(t.feedback()).contains("sem evidência"));
    }

    @Test
    void failedResultIsRedelegated() {
        AgentResult failed = AgentResult.failure(backendTask, AgentStatus.TIMEOUT, "lento", Map.of());
        Evaluation evaluation = evaluate(failed, 0);
        assertThat(evaluation.followUps()).singleElement()
                .satisfies(t -> assertThat(t.feedback()).contains("TIMEOUT"));
    }

    @Test
    void followUpLimitTurnsProblemIntoNote() {
        Evaluation evaluation = evaluate(Fixtures.success(AgentRole.BACKEND, 0.1), 1);
        assertThat(evaluation.sufficient()).isTrue();
        assertThat(evaluation.notes()).singleElement().asString().contains("Backend Agent");
    }

    @Test
    void cancelledResultIsNotRedelegated() {
        AgentResult cancelled = AgentResult.failure(backendTask, AgentStatus.CANCELLED, "cancelada", Map.of());
        Evaluation evaluation = evaluate(cancelled, 0);
        assertThat(evaluation.followUps()).isEmpty();
        assertThat(evaluation.notes()).hasSize(1);
    }
}
