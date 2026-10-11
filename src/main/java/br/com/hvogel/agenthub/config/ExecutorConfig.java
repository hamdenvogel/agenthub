package br.com.hvogel.agenthub.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExecutorConfig {

    /**
     * Virtual threads: agentes passam a maior parte do tempo esperando I/O (Claude, MCP).
     * O paralelismo efetivo é limitado por {@code agenthub.orchestrator.max-parallel-agents}.
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService agentHubExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("agenthub-", 0).factory());
    }
}
