package br.com.hvogel.agenthub.agents;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import br.com.hvogel.agenthub.config.AgentHubProperties;
import br.com.hvogel.agenthub.model.AgentRole;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Carrega o system prompt de cada agente. Default: {@code classpath:prompts/agents/<id>.md};
 * pode ser trocado por {@code agenthub.agents.<id>.system-prompt} (ex.: {@code file:./prompts/backend.md}).
 */
@Component
public class AgentPromptLoader {

    private final ResourceLoader resourceLoader;
    private final AgentHubProperties properties;

    public AgentPromptLoader(ResourceLoader resourceLoader, AgentHubProperties properties) {
        this.resourceLoader = resourceLoader;
        this.properties = properties;
    }

    public String load(AgentRole role) {
        String configured = properties.agent(role.id()).systemPrompt();
        return read(configured != null && !configured.isBlank() ? configured : "classpath:prompts/agents/" + role.id() + ".md");
    }

    public String read(String location) {
        Resource resource = resourceLoader.getResource(location);
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Prompt não encontrado: " + location, e);
        }
    }
}
