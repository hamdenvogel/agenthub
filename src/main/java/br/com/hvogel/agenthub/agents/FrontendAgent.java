package br.com.hvogel.agenthub.agents;

import br.com.hvogel.agenthub.model.AgentRole;
import org.springframework.stereotype.Component;

@Component
public class FrontendAgent extends AbstractClaudeAgent {

    public FrontendAgent(AgentDependencies deps) {
        super(AgentRole.FRONTEND,
                "Angular, TypeScript, RxJS, Signals, componentes, services, routing, guards, interceptors e acessibilidade.",
                deps);
    }
}
