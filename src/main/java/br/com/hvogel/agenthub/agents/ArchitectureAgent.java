package br.com.hvogel.agenthub.agents;

import br.com.hvogel.agenthub.model.AgentRole;
import org.springframework.stereotype.Component;

@Component
public class ArchitectureAgent extends AbstractClaudeAgent {

    public ArchitectureAgent(AgentDependencies deps) {
        super(AgentRole.ARCHITECTURE,
                "DDD, Clean/Hexagonal Architecture, SOLID, camadas, acoplamento, padrões, integração, infraestrutura e qualidade de código.",
                deps);
    }
}
