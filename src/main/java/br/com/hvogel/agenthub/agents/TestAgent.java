package br.com.hvogel.agenthub.agents;

import br.com.hvogel.agenthub.model.AgentRole;
import org.springframework.stereotype.Component;

@Component
public class TestAgent extends AbstractClaudeAgent {

    public TestAgent(AgentDependencies deps) {
        super(AgentRole.TEST,
                "JUnit, Mockito, testes de integração, E2E, Jasmine/Karma, cobertura, qualidade dos testes e CI.",
                deps);
    }
}
