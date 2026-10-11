package br.com.hvogel.agenthub.orchestrator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import br.com.hvogel.agenthub.config.AgentHubProperties;
import br.com.hvogel.agenthub.model.AgentResult;
import br.com.hvogel.agenthub.model.AgentRole;
import br.com.hvogel.agenthub.model.AgentStatus;
import br.com.hvogel.agenthub.model.AgentTask;
import br.com.hvogel.agenthub.model.Evaluation;
import br.com.hvogel.agenthub.model.Finding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * EVALUATE: decide se os resultados da rodada são suficientes. Critérios determinísticos
 * (auditáveis e sem custo de tokens): falha/timeout, confiança abaixo do mínimo, resumo
 * ausente, achados sem evidência ou análise sem nenhuma inspeção de código.
 * Insuficiente gera nova tarefa com feedback, até {@code max-follow-ups-per-agent}.
 */
@Component
public class Evaluator {

    private static final Logger log = LoggerFactory.getLogger(Evaluator.class);

    private final AgentHubProperties.Orchestrator config;

    public Evaluator(AgentHubProperties properties) {
        this.config = properties.orchestrator();
    }

    /**
     * @param executed       tarefas executadas nesta rodada
     * @param results        resultados consolidados até aqui
     * @param followUpsUsed  re-delegações já feitas por agente
     */
    public Evaluation evaluate(List<AgentTask> executed, Map<AgentRole, AgentResult> results, Map<AgentRole, Integer> followUpsUsed) {
        List<AgentTask> followUps = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (AgentTask task : executed) {
            AgentResult result = results.get(task.role());
            List<String> problems = problems(result);
            if (problems.isEmpty()) {
                continue;
            }
            String feedback = String.join("; ", problems);
            boolean canRetry = result != null && result.status() != AgentStatus.CANCELLED
                    && followUpsUsed.getOrDefault(task.role(), 0) < config.maxFollowUpsPerAgent();
            if (canRetry) {
                followUps.add(task.followUp(feedback + guidance(result)));
                log.info("event=evaluator.insufficient agent={} action=redelegate reasons=\"{}\"", task.role().id(), feedback);
            } else {
                notes.add(task.role().displayName() + ": " + feedback);
                log.info("event=evaluator.insufficient agent={} action=accept reasons=\"{}\"", task.role().id(), feedback);
            }
        }
        return new Evaluation(followUps.isEmpty(), followUps, notes);
    }

    List<String> problems(AgentResult result) {
        List<String> problems = new ArrayList<>();
        if (result == null) {
            problems.add("nenhum resultado retornado");
            return problems;
        }
        if (result.status() != AgentStatus.SUCCESS) {
            problems.add("execução terminou com " + result.status() + (result.error() != null ? " (" + result.error() + ")" : ""));
            return problems;
        }
        if (result.summary() == null || result.summary().isBlank()) {
            problems.add("resumo ausente");
        }
        if (result.confidence() < config.minConfidence()) {
            problems.add("confiança %.2f abaixo do mínimo %.2f".formatted(result.confidence(), config.minConfidence()));
        }
        long withoutEvidence = result.findings().stream().filter(f -> !f.hasEvidence()).count();
        if (!result.findings().isEmpty() && withoutEvidence * 2 > result.findings().size()) {
            problems.add(withoutEvidence + " de " + result.findings().size() + " achados sem evidência (arquivo/linhas)");
        }
        if (result.findings().isEmpty() && result.metadataLong("toolCalls") == 0
                && !((List<?>) result.metadata().getOrDefault("toolsAvailable", List.of())).isEmpty()) {
            problems.add("nenhum arquivo foi inspecionado e nenhum achado foi reportado");
        }
        return problems;
    }

    private static String guidance(AgentResult result) {
        if (result.status() == AgentStatus.TIMEOUT) {
            return ". Seja mais objetivo: leia apenas os arquivos centrais e responda antes do tempo limite.";
        }
        if (result.status() == AgentStatus.FAILED && result.error() != null && result.error().contains("max_tokens")) {
            return ". A resposta anterior foi cortada no limite de max_tokens: reporte só os achados mais relevantes, "
                    + "com descrições e trechos de evidência curtos.";
        }
        if (result.status() == AgentStatus.FAILED) {
            return ". Tente novamente com menos chamadas de ferramenta.";
        }
        if (Boolean.TRUE.equals(result.metadata().get("contextExhausted"))) {
            return ". A context window esgotou na rodada anterior: leia trechos menores (startLine/maxLines) e só os arquivos centrais.";
        }
        long undocumented = result.findings().stream().filter(f -> !f.hasEvidence()).map(Finding::title).count();
        return undocumented > 0
                ? ". Inclua evidências concretas (arquivo e linhas) ou remova achados não verificados."
                : ". Aprofunde a inspeção dos arquivos centrais da sua área antes de concluir.";
    }
}
