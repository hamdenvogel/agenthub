package br.com.hvogel.agenthub.mcp;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import br.com.hvogel.agenthub.claude.ContextBudget;

/**
 * Orçamento de uma execução de agente: chamadas de ferramenta (o loop interno de tool calling do modelo não tem
 * teto próprio) e a context window compartilhada com o {@code ClaudeService}, onde cada resultado é somado.
 */
public final class ToolBudget {

    private final int maxCalls;
    private final ContextBudget context;
    private final AtomicInteger used = new AtomicInteger();
    private final AtomicInteger rejectedInputs = new AtomicInteger();
    private final Map<String, AtomicInteger> bySource = new ConcurrentHashMap<>();

    public ToolBudget(int maxCalls) {
        this(maxCalls, ContextBudget.unlimited());
    }

    public ToolBudget(int maxCalls, ContextBudget context) {
        this.maxCalls = maxCalls;
        this.context = context != null ? context : ContextBudget.unlimited();
    }

    public boolean tryAcquire(String source) {
        if (used.incrementAndGet() > maxCalls) {
            used.decrementAndGet();
            return false;
        }
        bySource.computeIfAbsent(source, s -> new AtomicInteger()).incrementAndGet();
        return true;
    }

    void rejectInput() {
        rejectedInputs.incrementAndGet();
    }

    public ContextBudget context() {
        return context;
    }

    public int maxCalls() {
        return maxCalls;
    }

    public int used() {
        return used.get();
    }

    /** Chamadas recusadas por argumentos fora do Tool Input Schema. */
    public int rejectedInputs() {
        return rejectedInputs.get();
    }

    public Map<String, Integer> usedBySource() {
        Map<String, Integer> snapshot = new TreeMap<>();
        bySource.forEach((k, v) -> snapshot.put(k, v.get()));
        return snapshot;
    }
}
