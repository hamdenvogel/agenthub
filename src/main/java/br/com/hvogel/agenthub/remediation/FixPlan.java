package br.com.hvogel.agenthub.remediation;

import java.util.List;

/**
 * Resposta estruturada do agente de remediação para um achado.
 *
 * @param summary     o que a correção faz
 * @param changes     alterações de arquivos (vazio quando a correção é só manual)
 * @param manualSteps ações fora do código (rotacionar segredos, configurar variáveis, limpar histórico...)
 * @param risks       efeitos colaterais e o que testar
 * @param confidence  confiança na correção (0.0 a 1.0)
 */
public record FixPlan(String summary, List<FileChange> changes, List<String> manualSteps, List<String> risks,
                      double confidence) {

    public FixPlan {
        changes = changes != null ? List.copyOf(changes) : List.of();
        manualSteps = manualSteps != null ? List.copyOf(manualSteps) : List.of();
        risks = risks != null ? List.copyOf(risks) : List.of();
    }

    /**
     * @param path        caminho relativo à raiz do projeto
     * @param action      {@code MODIFY} (edições) ou {@code CREATE} (arquivo novo com {@code content})
     * @param explanation por que o arquivo muda
     * @param edits       substituições de trechos exatos (MODIFY)
     * @param content     conteúdo completo (CREATE)
     */
    public record FileChange(String path, String action, String explanation, List<Edit> edits, String content) {
        public FileChange {
            edits = edits != null ? List.copyOf(edits) : List.of();
        }

        public boolean isCreate() {
            return "CREATE".equalsIgnoreCase(action);
        }
    }

    /**
     * @param search  trecho exato e único do arquivo atual
     * @param replace texto que substitui o trecho
     */
    public record Edit(String search, String replace) {
    }
}
