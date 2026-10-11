package br.com.hvogel.agenthub.claude;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ChatClient sem system prompt nem ferramentas default: cada chamada informa os seus,
 * porque cada agente tem instruções e privilégios próprios.
 * Modelo, max-tokens, timeout e API key vêm de {@code spring.ai.anthropic.*} (variáveis de ambiente).
 */
@Configuration
public class ClaudeConfiguration {

    @Bean
    public ChatClient agentHubChatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
