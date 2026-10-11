package br.com.hvogel.agenthub.orchestrator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import br.com.hvogel.agenthub.model.AgentAnalysis;
import br.com.hvogel.agenthub.model.AgentResult;
import br.com.hvogel.agenthub.model.AgentRole;
import br.com.hvogel.agenthub.model.AgentStatus;
import br.com.hvogel.agenthub.model.AnalysisReport.ReportSection;
import br.com.hvogel.agenthub.model.Evidence;
import br.com.hvogel.agenthub.model.Finding;
import br.com.hvogel.agenthub.model.HubConsolidation;
import br.com.hvogel.agenthub.model.HubConsolidation.AgentOutput;
import br.com.hvogel.agenthub.model.HubConsolidation.Item;
import br.com.hvogel.agenthub.model.HubConsolidation.ItemType;
import br.com.hvogel.agenthub.model.HubConsolidation.Outcome;
import br.com.hvogel.agenthub.model.HubConsolidation.Totals;
import br.com.hvogel.agenthub.model.Severity;
import org.springframework.stereotype.Component;

/**
 * AGGREGATE: consolida os resultados dos especialistas em seções do relatório,
 * remove achados duplicados entre agentes, atribui ids e ordena por severidade.
 */
@Component
public class ResultAggregator {

    /** Consolidação pronta para síntese e montagem do relatório. */
    public record Aggregation(
            Map<AgentRole, ReportSection> sections,
            ReportSection codeQuality,
            List<Finding> findings,
            List<String> recommendations,
            Map<Severity, Long> findingsBySeverity,
            HubConsolidation consolidation) {
    }

    private static final Comparator<Finding> BY_SEVERITY = Comparator
            .comparing((Finding f) -> f.severity().rank()).reversed()
            .thenComparing(f -> f.agent() != null ? f.agent().ordinal() : 99);

    public Aggregation aggregate(Map<AgentRole, AgentResult> results) {
        Map<String, Finding> unique = uniqueByKey(results.values().stream()
                .flatMap(r -> r.findings().stream())
                .sorted(BY_SEVERITY)
                .toList());
        List<Finding> findings = assignIds(List.copyOf(unique.values()));
        Map<String, String> idByKey = new HashMap<>();
        List<String> keys = List.copyOf(unique.keySet());
        for (int i = 0; i < keys.size(); i++) {
            idByKey.put(keys.get(i), findings.get(i).id());
        }

        Map<AgentRole, ReportSection> sections = new EnumMap<>(AgentRole.class);
        for (AgentRole role : AgentRole.values()) {
            AgentResult result = results.get(role);
            if (result == null) {
                sections.put(role, new ReportSection(role.reportSection(), false, null,
                        "Área não analisada nesta execução.", List.of(), 0.0));
                continue;
            }
            List<Finding> sectionFindings = findings.stream().filter(f -> f.agent() == role).toList();
            String summary = result.status() == AgentStatus.SUCCESS
                    ? result.summary()
                    : role.displayName() + " não concluiu (" + result.status() + "): " + result.error();
            sections.put(role, new ReportSection(role.reportSection(), result.status() == AgentStatus.SUCCESS,
                    result.status(), summary, sectionFindings, result.confidence()));
        }

        List<Finding> quality = findings.stream().filter(ResultAggregator::isCodeQuality).toList();
        ReportSection codeQuality = new ReportSection("Code Quality", !quality.isEmpty(), null,
                quality.isEmpty() ? "Nenhum achado específico de qualidade de código." : null, quality, 0.0);

        Set<String> recommendations = new LinkedHashSet<>();
        HubConsolidation consolidation = consolidate(results, unique, idByKey, recommendations);

        Map<Severity, Long> counts = new LinkedHashMap<>();
        for (Severity severity : Severity.values()) {
            counts.put(severity, findings.stream().filter(f -> f.severity() == severity).count());
        }
        return new Aggregation(sections, codeQuality, findings, List.copyOf(recommendations), counts, consolidation);
    }

    /**
     * Registra cada item recebido dos agentes e o seu destino. Um achado fica no relatório quando é a instância
     * escolhida para a sua chave; os demais com a mesma chave apontam para o id dela.
     */
    private static HubConsolidation consolidate(Map<AgentRole, AgentResult> results, Map<String, Finding> unique,
                                                Map<String, String> idByKey, Set<String> recommendations) {
        List<AgentOutput> agents = new ArrayList<>();
        List<Item> items = new ArrayList<>();
        Set<String> files = new LinkedHashSet<>();
        int received = 0;
        int recommendationsReceived = 0;
        int succeeded = 0;
        for (AgentResult r : results.values().stream().sorted(Comparator.comparing(AgentResult::role)).toList()) {
            int kept = 0;
            for (Finding f : r.findings()) {
                String key = key(f);
                boolean isKept = unique.get(key) == f;
                kept += isKept ? 1 : 0;
                items.add(new Item(ItemType.FINDING, r.role().id(), r.agentName(), f.severity(), f.title(),
                        f.category(), primaryFile(f), isKept ? Outcome.KEPT : Outcome.DUPLICATE, idByKey.get(key)));
            }
            int recs = 0;
            for (String recommendation : r.recommendations()) {
                String text = recommendation == null ? "" : recommendation.trim();
                if (text.isEmpty()) {
                    continue;
                }
                recs++;
                items.add(new Item(ItemType.RECOMMENDATION, r.role().id(), r.agentName(), null, text, null, null,
                        recommendations.add(text) ? Outcome.KEPT : Outcome.DUPLICATE, null));
            }
            boolean success = r.status() == AgentStatus.SUCCESS;
            succeeded += success ? 1 : 0;
            received += r.findings().size();
            recommendationsReceived += recs;
            files.addAll(r.evidence());
            int violations = r.metadata().get("schemaViolations") instanceof List<?> list ? list.size() : 0;
            agents.add(new AgentOutput(r.role().id(), r.agentName(), r.status(), r.metadataLong("round"),
                    success ? AgentAnalysis.class.getSimpleName() : null, r.summary(), r.confidence(), r.severity(),
                    r.findings().size(), kept, r.findings().size() - kept, recs, r.evidence().size(), violations,
                    r.error()));
        }
        Totals totals = new Totals(results.size(), succeeded, results.size() - succeeded, received, unique.size(),
                received - unique.size(), recommendationsReceived, recommendations.size(), files.size());
        return new HubConsolidation(totals, agents, items);
    }

    /** Mesmo título normalizado + mesmo arquivo principal = mesmo achado; mantém o de maior severidade. */
    static List<Finding> deduplicate(List<Finding> sortedFindings) {
        return new ArrayList<>(uniqueByKey(sortedFindings).values());
    }

    private static Map<String, Finding> uniqueByKey(List<Finding> sortedFindings) {
        Map<String, Finding> unique = new LinkedHashMap<>();
        for (Finding finding : sortedFindings) {
            unique.putIfAbsent(key(finding), finding);
        }
        return unique;
    }

    private static List<Finding> assignIds(List<Finding> findings) {
        Map<AgentRole, Integer> counters = new EnumMap<>(AgentRole.class);
        List<Finding> withIds = new ArrayList<>();
        for (Finding finding : findings) {
            AgentRole role = finding.agent() != null ? finding.agent() : AgentRole.ARCHITECTURE;
            int next = counters.merge(role, 1, Integer::sum);
            withIds.add(finding.withId("%s-%03d".formatted(role.findingPrefix(), next)));
        }
        return withIds;
    }

    private static String key(Finding finding) {
        String title = finding.title() == null ? "" : finding.title().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return title + "|" + primaryFile(finding);
    }

    private static String primaryFile(Finding finding) {
        return finding.evidence().stream().filter(Evidence::hasFile).map(Evidence::file).findFirst().orElse("");
    }

    private static boolean isCodeQuality(Finding finding) {
        String category = finding.category() == null ? "" : finding.category().toLowerCase(Locale.ROOT);
        return category.contains("quality") || category.contains("qualidade") || category.contains("smell")
                || category.contains("duplica") || category.contains("maintainab") || category.contains("manutenib");
    }
}
