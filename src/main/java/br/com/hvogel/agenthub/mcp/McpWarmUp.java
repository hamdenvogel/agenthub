package br.com.hvogel.agenthub.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Inicializa as conexões MCP em background após o startup, para que a primeira análise
 * não pague o custo de subir os servidores e para que problemas apareçam cedo no log.
 */
@Component
@ConditionalOnProperty(prefix = "agenthub.mcp", name = "warm-up", havingValue = "true", matchIfMissing = true)
public class McpWarmUp {

    private static final Logger log = LoggerFactory.getLogger(McpWarmUp.class);

    private final McpToolRegistry registry;

    public McpWarmUp(McpToolRegistry registry) {
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        Thread.ofVirtual().name("agenthub-mcp-warmup").start(() -> registry.statuses().forEach(s ->
                log.info("event=tool.source.status source={} type={} enabled={} available={} tools={} detail=\"{}\"",
                        s.id(), s.type(), s.enabled(), s.available(), s.tools(), s.detail())));
    }
}
