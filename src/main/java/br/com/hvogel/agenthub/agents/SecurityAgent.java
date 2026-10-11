package br.com.hvogel.agenthub.agents;

import br.com.hvogel.agenthub.model.AgentRole;
import org.springframework.stereotype.Component;

@Component
public class SecurityAgent extends AbstractClaudeAgent {

    public SecurityAgent(AgentDependencies deps) {
        super(AgentRole.SECURITY,
                "OWASP, Spring Security, autenticação, autorização, JWT, CORS, secrets, dependências vulneráveis e segurança do frontend.",
                deps);
    }
}
