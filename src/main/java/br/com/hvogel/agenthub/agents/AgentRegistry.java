package br.com.hvogel.agenthub.agents;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import br.com.hvogel.agenthub.config.AgentHubProperties;
import br.com.hvogel.agenthub.model.AgentRole;
import org.springframework.stereotype.Component;

/**
 * Agentes disponíveis ao Hub: todos os beans {@link Agent} habilitados em
 * {@code agenthub.agents.<id>.enabled}.
 */
@Component
public class AgentRegistry {

    private final Map<AgentRole, Agent> agents = new EnumMap<>(AgentRole.class);

    public AgentRegistry(List<Agent> agentBeans, AgentHubProperties properties) {
        for (Agent agent : agentBeans) {
            if (Boolean.TRUE.equals(properties.agent(agent.role().id()).enabled())) {
                Agent previous = agents.put(agent.role(), agent);
                if (previous != null) {
                    throw new IllegalStateException("Mais de um agente para o papel " + agent.role());
                }
            }
        }
    }

    public Optional<Agent> find(AgentRole role) {
        return Optional.ofNullable(agents.get(role));
    }

    public Collection<Agent> enabled() {
        return agents.values();
    }

    public List<AgentRole> enabledRoles() {
        return List.copyOf(agents.keySet());
    }
}
