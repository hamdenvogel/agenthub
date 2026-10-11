package br.com.hvogel.agenthub.remediation;

import java.time.Instant;
import java.util.List;

import br.com.hvogel.agenthub.model.Severity;

/**
 * Proposta de correção de um achado, do pedido até a aplicação (ou reversão) no projeto.
 *
 * @param id           identificador
 * @param analysisId   análise de origem do achado
 * @param findingId    achado (ex.: {@code SEC-001})
 * @param findingTitle título do achado
 * @param severity     severidade do achado
 * @param projectRoot  raiz do projeto em que a correção será gravada
 * @param status       etapa atual
 * @param createdAt    criação
 * @param updatedAt    última mudança de status
 * @param summary      o que a correção faz
 * @param files        arquivos alterados, com diff para revisão
 * @param manualSteps  ações fora do código
 * @param risks        efeitos colaterais e o que testar
 * @param confidence   confiança do agente
 * @param error        motivo da falha
 * @param model        modelo Claude usado
 * @param inputTokens  tokens de entrada
 * @param outputTokens tokens de saída
 * @param toolCalls    leituras feitas pelo agente
 */
public record RemediationProposal(
        String id, String analysisId, String findingId, String findingTitle, Severity severity, String projectRoot,
        Status status, Instant createdAt, Instant updatedAt, String summary, List<ProposedFile> files,
        List<String> manualSteps, List<String> risks, double confidence, String error, String model,
        long inputTokens, long outputTokens, int toolCalls) {

    public enum Status {
        /** O agente está lendo o projeto e montando a correção. */
        GENERATING,
        /** Diff pronto para revisão. */
        READY,
        /** Sem alteração de código: só passos manuais. */
        MANUAL_ONLY,
        FAILED,
        APPLIED,
        REVERTED;

        public boolean isActive() {
            return this == GENERATING || this == READY || this == MANUAL_ONLY || this == APPLIED;
        }
    }

    /**
     * @param path           caminho relativo à raiz
     * @param created        arquivo novo
     * @param explanation    por que o arquivo muda
     * @param edits          substituições reaplicadas no momento da gravação
     * @param content        conteúdo do arquivo novo
     * @param originalSha256 hash do arquivo quando a proposta foi gerada (detecta alterações posteriores)
     * @param newSha256      hash do resultado esperado
     * @param diff           diff unificado, com segredos mascarados
     * @param additions      linhas incluídas
     * @param deletions      linhas removidas
     */
    public record ProposedFile(String path, boolean created, String explanation, List<FixPlan.Edit> edits,
                               String content, String originalSha256, String newSha256, String diff,
                               int additions, int deletions) {
        public ProposedFile {
            edits = edits != null ? List.copyOf(edits) : List.of();
        }
    }

    public RemediationProposal {
        files = files != null ? List.copyOf(files) : List.of();
        manualSteps = manualSteps != null ? List.copyOf(manualSteps) : List.of();
        risks = risks != null ? List.copyOf(risks) : List.of();
    }

    static RemediationProposal generating(String id, String analysisId, String findingId, String findingTitle,
                                          Severity severity, String projectRoot) {
        Instant now = Instant.now();
        return new RemediationProposal(id, analysisId, findingId, findingTitle, severity, projectRoot,
                Status.GENERATING, now, now, null, List.of(), List.of(), List.of(), 0, null, null, 0, 0, 0);
    }

    RemediationProposal withStatus(Status next) {
        return new RemediationProposal(id, analysisId, findingId, findingTitle, severity, projectRoot, next, createdAt,
                Instant.now(), summary, files, manualSteps, risks, confidence, error, model, inputTokens,
                outputTokens, toolCalls);
    }

    RemediationProposal failed(String reason) {
        return new RemediationProposal(id, analysisId, findingId, findingTitle, severity, projectRoot, Status.FAILED,
                createdAt, Instant.now(), summary, files, manualSteps, risks, confidence, reason, model, inputTokens,
                outputTokens, toolCalls);
    }

    RemediationProposal planned(FixPlan plan, List<ProposedFile> proposedFiles, String usedModel, long usedInput,
                                long usedOutput, int usedToolCalls) {
        return new RemediationProposal(id, analysisId, findingId, findingTitle, severity, projectRoot,
                proposedFiles.isEmpty() ? Status.MANUAL_ONLY : Status.READY, createdAt, Instant.now(), plan.summary(),
                proposedFiles, plan.manualSteps(), plan.risks(), plan.confidence(), null, usedModel, usedInput,
                usedOutput, usedToolCalls);
    }
}
