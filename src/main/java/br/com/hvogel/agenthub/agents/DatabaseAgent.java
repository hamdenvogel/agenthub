package br.com.hvogel.agenthub.agents;

import br.com.hvogel.agenthub.model.AgentRole;
import org.springframework.stereotype.Component;

@Component
public class DatabaseAgent extends AbstractClaudeAgent {

    public DatabaseAgent(AgentDependencies deps) {
        super(AgentRole.DATABASE,
                "Modelagem relacional, SQL, PostgreSQL/Oracle, JPA/Hibernate, índices, queries, migrations e performance.",
                deps);
    }
}
