package br.com.hvogel.agenthub.remediation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Correção assistida dos achados (prefixo {@code agenthub.remediation}).
 *
 * @param enabled      recurso disponível (ainda exige o consentimento do usuário por projeto)
 * @param storeDir     propostas, backups e consentimentos (JSON, fora do projeto analisado)
 * @param maxToolCalls orçamento de leituras do agente de remediação por proposta
 * @param maxFiles     arquivos alterados por proposta
 */
@ConfigurationProperties("agenthub.remediation")
public record RemediationProperties(Boolean enabled, String storeDir, int maxToolCalls, int maxFiles) {

    public RemediationProperties {
        enabled = enabled == null || enabled;
        storeDir = storeDir != null && !storeDir.isBlank() ? storeDir : "data/remediations";
        maxToolCalls = maxToolCalls > 0 ? maxToolCalls : 25;
        maxFiles = maxFiles > 0 ? maxFiles : 8;
    }
}
