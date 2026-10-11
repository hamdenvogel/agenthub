package br.com.hvogel.agenthub.analysis;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Armazenamento em memória das análises da sessão. Os relatórios concluídos também
 * ficam em disco ({@code agenthub.reports.output-dir}), então nada se perde num restart.
 */
@Component
public class AnalysisRepository {

    static final int MAX_RUNS = 50;

    private final Map<String, AnalysisRun> runs = new ConcurrentHashMap<>();

    public void save(AnalysisRun run) {
        runs.put(run.id(), run);
        evictOldFinished();
    }

    public Optional<AnalysisRun> find(String id) {
        return Optional.ofNullable(runs.get(id));
    }

    public List<AnalysisRun> findAll() {
        return runs.values().stream().sorted(Comparator.comparing(AnalysisRun::createdAt).reversed()).toList();
    }

    private void evictOldFinished() {
        if (runs.size() <= MAX_RUNS) {
            return;
        }
        runs.values().stream()
                .filter(r -> r.status().isFinished())
                .sorted(Comparator.comparing(AnalysisRun::createdAt))
                .limit(runs.size() - (long) MAX_RUNS)
                .toList()
                .forEach(r -> runs.remove(r.id()));
    }
}
