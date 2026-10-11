package br.com.hvogel.agenthub.orchestrator;

import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * Cancelamento cooperativo de uma análise: o Hub consulta o token entre as fases e
 * as execuções de agente em andamento são interrompidas.
 */
public final class CancellationToken {

    private volatile boolean cancelled;
    private final Set<Future<?>> running = ConcurrentHashMap.newKeySet();

    public void cancel() {
        cancelled = true;
        running.forEach(f -> f.cancel(true));
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void throwIfCancelled() {
        if (cancelled) {
            throw new CancellationException("Análise cancelada");
        }
    }

    void register(Future<?> future) {
        running.add(future);
        if (cancelled) {
            future.cancel(true);
        }
    }

    void unregister(Future<?> future) {
        running.remove(future);
    }
}
