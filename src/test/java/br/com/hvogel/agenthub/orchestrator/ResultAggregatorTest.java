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
import br.com.hvogel.agenthub.model.Evidence;
import br.com.hvogel.agenthub.model.Finding;
import br.com.hvogel.agenthub.model.HubConsolidation;
import br.com.hvogel.agenthub.model.HubConsolidation.AgentOutput;
import br.com.hvogel.agenthub.model.HubConsolidation.Item;
import br.com.hvogel.agenthub.model.HubConsolidation.ItemType;
import br.com.hvogel.agenthub.model.HubConsolidation.Outcome;
import br.com.hvogel.agenthub.model.Severity;
import br.com.hvogel.agenthub.orchestrator.ResultAggregator.Aggregation;
import br.com.hvogel.agenthub.support.Fixtures;
import org.junit.jupiter.api.Test;

class ResultAggregatorTest {

    private final ResultAggregator aggregator = new ResultAggregator();

    @Test
    void consolidatesSortsDeduplicatesAndAssignsIds() {
        Map<AgentRole, AgentResult> results = new EnumMap<>(AgentRole.class);
        results.put(AgentRole.BACKEND, Fixtures.success(AgentRole.BACKEND, 0.8,
                Fixtures.finding("Segredo JWT hardcoded", Severity.MEDIUM, "Jwt.java"),
                Fixtures.finding("Service grande", Severity.LOW, "S.java")));
        results.put(AgentRole.SECURITY, Fixtures.success(AgentRole.SECURITY, 0.9,
                Fixtures.finding("Segredo JWT hardcoded", Severity.CRITICAL, "Jwt.java")));

        Aggregation aggregation = aggregator.aggregate(results);

        assertThat(aggregation.findings()).extracting(Finding::id).containsExactly("SEC-001", "BCK-001");
        assertThat(aggregation.findings().get(0).severity()).isEqualTo(Severity.CRITICAL);
        assertThat(aggregation.findingsBySeverity()).containsEntry(Severity.CRITICAL, 1L).containsEntry(Severity.LOW, 1L);
        assertThat(aggregation.sections().get(AgentRole.BACKEND).findings()).hasSize(1);
        assertThat(aggregation.sections().get(AgentRole.FRONTEND).analyzed()).isFalse();
        assertThat(aggregation.recommendations()).containsExactly("Recomendação geral");
    }

    @Test
    void consolidationRecordsWhatEachAgentDeliveredAndWhereItWent() {
        Map<AgentRole, AgentResult> results = new EnumMap<>(AgentRole.class);
        results.put(AgentRole.BACKEND, Fixtures.success(AgentRole.BACKEND, 0.8,
                Fixtures.finding("Segredo JWT hardcoded", Severity.MEDIUM, "Jwt.java"),
                Fixtures.finding("Service grande", Severity.LOW, "S.java")));
        results.put(AgentRole.SECURITY, Fixtures.success(AgentRole.SECURITY, 0.9,
                Fixtures.finding("Segredo JWT hardcoded", Severity.CRITICAL, "Jwt.java")));
        results.put(AgentRole.DATABASE, AgentResult.failure(AgentTask.initial(AgentRole.DATABASE, "o", null, null),
                AgentStatus.TIMEOUT, "demorou", Map.of()));

        HubConsolidation c = aggregator.aggregate(results).consolidation();

        assertThat(c.totals()).isEqualTo(new HubConsolidation.Totals(3, 2, 1, 3, 2, 1, 2, 1, 2));
        assertThat(c.agents()).extracting(AgentOutput::agent).containsExactly("backend", "database", "security");
        AgentOutput backend = c.agents().getFirst();
        assertThat(backend.outputSchema()).isEqualTo("AgentAnalysis");
        assertThat(backend.findingsReceived()).isEqualTo(2);
        assertThat(backend.findingsMerged()).isEqualTo(1);
        assertThat(c.agents().get(1).outputSchema()).isNull();
        assertThat(c.agents().get(1).error()).isEqualTo("demorou");

        Item mergedJwt = c.items().getFirst();
        assertThat(mergedJwt.type()).isEqualTo(ItemType.FINDING);
        assertThat(mergedJwt.outcome()).isEqualTo(Outcome.DUPLICATE);
        assertThat(mergedJwt.findingId()).isEqualTo("SEC-001");
        assertThat(c.items()).filteredOn(i -> i.type() == ItemType.RECOMMENDATION)
                .extracting(Item::outcome).containsExactly(Outcome.KEPT, Outcome.DUPLICATE);
        assertThat(c.items()).filteredOn(i -> i.agent().equals("security")).filteredOn(i -> i.type() == ItemType.FINDING)
                .singleElement().satisfies(i -> {
                    assertThat(i.outcome()).isEqualTo(Outcome.KEPT);
                    assertThat(i.file()).isEqualTo("Jwt.java");
                });
    }

    @Test
    void codeQualitySectionCollectsQualityFindings() {
        FindingDraft quality = new FindingDraft("Duplicação", "d", Severity.LOW, "code-quality",
                List.of(new Evidence("A.java", "1", "x")), "r");
        Map<AgentRole, AgentResult> results = Map.of(AgentRole.ARCHITECTURE, Fixtures.success(AgentRole.ARCHITECTURE, 0.8, quality));

        Aggregation aggregation = aggregator.aggregate(results);

        assertThat(aggregation.codeQuality().findings()).singleElement()
                .satisfies(f -> assertThat(f.id()).isEqualTo("ARC-001"));
    }

    @Test
    void failedAgentSectionExplainsFailure() {
        AgentResult failed = AgentResult.failure(AgentTask.initial(AgentRole.DATABASE, "o", null, null),
                AgentStatus.TIMEOUT, "demorou", Map.of());

        Aggregation aggregation = aggregator.aggregate(Map.of(AgentRole.DATABASE, failed));

        assertThat(aggregation.sections().get(AgentRole.DATABASE).analyzed()).isFalse();
        assertThat(aggregation.sections().get(AgentRole.DATABASE).summary()).contains("TIMEOUT").contains("demorou");
    }
}
