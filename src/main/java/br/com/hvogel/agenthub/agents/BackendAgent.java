package br.com.hvogel.agenthub.agents;

import br.com.hvogel.agenthub.model.AgentRole;
import org.springframework.stereotype.Component;

@Component
public class BackendAgent extends AbstractClaudeAgent {

    public BackendAgent(AgentDependencies deps) {
        super(AgentRole.BACKEND,
                "Java, Spring Boot, REST, JPA/Hibernate, SOLID, design patterns, tratamento de erros e arquitetura backend.",
                deps);
    }
}
