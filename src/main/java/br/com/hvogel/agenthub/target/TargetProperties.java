package br.com.hvogel.agenthub.target;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Projetos-alvo cadastrados pela UI (prefixo {@code agenthub.targets}).
 *
 * @param storeFile    arquivo JSON com os alvos e o alvo ativo
 * @param allowedRoots pastas-base onde os alvos podem ficar; vazio = pasta-pai do alvo padrão
 */
@ConfigurationProperties("agenthub.targets")
public record TargetProperties(String storeFile, List<String> allowedRoots) {

    public TargetProperties {
        storeFile = storeFile != null && !storeFile.isBlank() ? storeFile : "data/targets.json";
        allowedRoots = allowedRoots != null
                ? allowedRoots.stream().filter(r -> r != null && !r.isBlank()).map(String::trim).toList()
                : List.of();
    }
}
