package br.com.hvogel.agenthub.api.dto;

import java.util.List;

/**
 * @param id          id do papel
 * @param name        nome do agente
 * @param description especialidade
 * @param toolSources fontes de ferramentas permitidas
 */
public record AgentInfo(String id, String name, String description, List<String> toolSources) {
}
